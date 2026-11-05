#!/usr/bin/env python3
"""
Probe the control channel of the Sony XQZ-IV01 Vlog Monitor.

What this does
--------------
The monitor is a USB-C DisplayPort Alt-Mode sink plus a small vendor USB
device (VID:PID 054c:0e48).  The camera apps talk to the vendor device over a
bulk OUT/IN pair on interface 0 with raw ASCII commands:

    phone -> monitor : "show"  (enables the DP sink + panel; 1.000 s watchdog)
                       "stop"  (drops the DP link / blanks the panel)
    monitor -> phone : "disp_on" / "disp_off"  (ACKs, ignored by Sony's apps)
                       "flip"                 (FLIP button pressed)

The monitor does not even appear as a DisplayPort sink until "show" is sent;
refreshing it every 400 ms (as Sony's app does) keeps HPD asserted.  The video
itself still travels over DP Alt Mode, not over this USB channel, so a USB-only
port will show the control device but no display.

This script only touches the control device (054c:0e48).  With "show" active and
a mic plugged, the monitor also enumerates a separate USB Audio Class device
(054c:0e49, VIA Labs, 48 kHz/16-bit stereo); it is not handled here.

Requirements
------------
    pip install pyusb
    Linux: permission to the USB device (root, or a udev rule).
    Android/Termux: root is normally required to open /dev/bus/usb.

Usage
-----
    python3 xqz_probe.py                # dump descriptors, heartbeat, print RX
    python3 xqz_probe.py --duration 10  # run for 10 s, send "stop", exit
    python3 xqz_probe.py --no-show      # only dump descriptors and listen
    python3 xqz_probe.py --interface 1  # override interface index

Verified on real hardware: interface 0 is vendor-specific (0xff/0x01/0x01) with
bulk OUT 0x02 and bulk IN 0x81, 16-byte max packets.  "show" produces "disp_on"
and must be refreshed within 1.000 s (the apps use 400 ms); the DP sink appears
~0.35 s after the first "show" and disappears ~0.14 s after the watchdog expires.
"stop" produces "disp_off"; the FLIP button produces "flip".  Validate on your own
unit before relying on it.  Press Ctrl-C to send "stop" and exit.
"""

import argparse
import sys
import threading
import time

try:
    import usb.core
    import usb.util
except ImportError:  # pragma: no cover
    sys.exit("pyusb is required: pip install pyusb")

DEFAULT_VID = 0x054C
DEFAULT_PID = 0x0E48
DEFAULT_INTERFACE = 0
DEFAULT_INTERVAL = 0.4


def hexid(value):
    return f"0x{value:04x}"


def dump_device(dev):
    print(f"device {hexid(dev.idVendor)}:{hexid(dev.idProduct)} "
          f"bus={dev.bus} address={dev.address}")
    try:
        print(f"  manufacturer: {dev.manufacturer}")
        print(f"  product     : {dev.product}")
        print(f"  serial      : {dev.serial_number}")
    except (usb.core.USBError, ValueError):
        pass
    for cfg in dev:
        print(f"  config {cfg.bConfigurationValue}: "
              f"{cfg.bNumInterfaces} interface(s)")
        for intf in cfg:
            print(f"    interface {intf.bInterfaceNumber} "
                  f"alt={intf.bAlternateSetting} "
                  f"class={intf.bInterfaceClass:#04x}/"
                  f"{intf.bInterfaceSubClass:#04x}/"
                  f"{intf.bInterfaceProtocol:#04x}")
            for ep in intf:
                etype = usb.util.endpoint_type(ep.bmAttributes)
                edir = usb.util.endpoint_direction(ep.bEndpointAddress)
                type_name = {
                    usb.util.ENDPOINT_TYPE_CTRL: "control",
                    usb.util.ENDPOINT_TYPE_ISO: "isochronous",
                    usb.util.ENDPOINT_TYPE_BULK: "bulk",
                    usb.util.ENDPOINT_TYPE_INTR: "interrupt",
                }.get(etype, f"type{etype}")
                dir_name = "IN" if edir == usb.util.ENDPOINT_IN else "OUT"
                print(f"      endpoint {hexid(ep.bEndpointAddress)} "
                      f"{dir_name:3s} {type_name:11s} "
                      f"maxPacket={ep.wMaxPacketSize}")


def find_bulk_pair(intf):
    ep_in = ep_out = None
    for ep in intf:
        if usb.util.endpoint_type(ep.bmAttributes) != usb.util.ENDPOINT_TYPE_BULK:
            continue
        if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN:
            ep_in = ep
        else:
            ep_out = ep
    return ep_in, ep_out


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--vid", type=lambda s: int(s, 0), default=DEFAULT_VID)
    parser.add_argument("--pid", type=lambda s: int(s, 0), default=DEFAULT_PID)
    parser.add_argument("--interface", type=int, default=None,
                        help="interface index (default: auto, prefer 0)")
    parser.add_argument("--interval", type=float, default=DEFAULT_INTERVAL,
                        help="seconds between 'show' heartbeats (default 0.4)")
    parser.add_argument("--no-show", action="store_true",
                        help="do not send 'show'; just dump descriptors and read")
    parser.add_argument("--duration", type=float, default=0,
                        help="run for N seconds, then send 'stop' and exit (0 = forever)")
    args = parser.parse_args()

    # Line-buffer stdout so redirected logs are not lost if the process is killed.
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except AttributeError:  # pragma: no cover
        pass

    dev = usb.core.find(idVendor=args.vid, idProduct=args.pid)
    if dev is None:
        sys.exit(f"device {hexid(args.vid)}:{hexid(args.pid)} not found "
                 "(is the monitor connected and the kernel driver detached?)")

    dump_device(dev)

    cfg = dev.get_active_configuration()
    candidates = []
    if args.interface is not None:
        candidates = [(args.interface, 0)]
    else:
        candidates = [(i.bInterfaceNumber, i.bAlternateSetting) for i in cfg]

    chosen = None
    for num, alt in candidates:
        intf = cfg[(num, alt)]
        ep_in, ep_out = find_bulk_pair(intf)
        if ep_in is not None and ep_out is not None:
            chosen = (intf, ep_in, ep_out)
            break

    if chosen is None:
        sys.exit("no interface with a bulk IN/OUT pair found; "
                 "run with --interface to try a specific one")

    intf, ep_in, ep_out = chosen
    print(f"\nusing interface {intf.bInterfaceNumber} "
          f"(bulk OUT {hexid(ep_out.bEndpointAddress)}, "
          f"bulk IN {hexid(ep_in.bEndpointAddress)})")

    try:
        if dev.is_kernel_driver_active(intf.bInterfaceNumber):
            dev.detach_kernel_driver(intf.bInterfaceNumber)
            print("detached kernel driver")
    except (NotImplementedError, usb.core.USBError):
        pass

    usb.util.claim_interface(dev, intf.bInterfaceNumber)
    stop = threading.Event()
    started = time.monotonic()

    def expired():
        return args.duration > 0 and (time.monotonic() - started) >= args.duration

    def reader():
        while not stop.is_set():
            try:
                data = ep_in.read(ep_in.wMaxPacketSize or 64, timeout=200)
                raw = bytes(data)
                text = raw.decode("utf-8", errors="replace").strip()
                print(f"[{time.monotonic() - started:6.2f}s] RX {raw!r}  ->  {text!r}")
                for line in text.replace("\r", "\n").splitlines():
                    if line == "flip":
                        print("   [FLIP button pressed]")
            except usb.core.USBTimeoutError:
                continue
            except usb.core.USBError as exc:
                if not stop.is_set():
                    print(f"RX error: {exc}")
                return

    thread = threading.Thread(target=reader, daemon=True)
    thread.start()

    try:
        if args.no_show:
            print("listening; Ctrl-C to exit")
            while not expired():
                time.sleep(0.1)
        else:
            print(f"sending 'show' every {args.interval:g}s; Ctrl-C to send 'stop'")
            while not expired():
                ep_out.write(b"show", timeout=50)
                time.sleep(args.interval)
        if args.duration > 0:
            print(f"duration reached ({args.duration:g}s)")
    except KeyboardInterrupt:
        print("\ninterrupted")
    finally:
        try:
            print("TX b'stop'")
            ep_out.write(b"stop", timeout=50)
            time.sleep(0.3)
        except usb.core.USBError:
            pass
        stop.set()
        time.sleep(0.1)
        try:
            usb.util.release_interface(dev, intf.bInterfaceNumber)
        except usb.core.USBError:
            pass
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
