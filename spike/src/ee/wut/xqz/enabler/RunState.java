package ee.wut.xqz.enabler;

/** Lifecycle state of the heartbeat service, shared with the UI and tile. */
public enum RunState {
    STOPPED,
    WAITING_DEVICE,
    WAITING_PERMISSION,
    RUNNING,
    RETRYING;

    public boolean isActive() {
        return this != STOPPED;
    }

    public boolean isWaiting() {
        return this == WAITING_DEVICE || this == WAITING_PERMISSION || this == RETRYING;
    }

    /** A worker may publish state only while the service is alive and not stopping. */
    public static boolean mayPublish(boolean serviceActive, boolean stopping) {
        return serviceActive && !stopping;
    }
}
