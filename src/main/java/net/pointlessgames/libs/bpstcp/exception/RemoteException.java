package net.pointlessgames.libs.bpstcp.exception;

public class RemoteException extends RuntimeException {
    private final String remoteExceptionName;
    private final String remoteMessage;

    public RemoteException(String remoteExceptionName, String remoteMessage, StackTraceElement[] callSiteStackTrace) {
        super("Remote exception "+remoteExceptionName+": "+remoteMessage);
        this.remoteExceptionName = remoteExceptionName;
        this.remoteMessage = remoteMessage;
        StackTraceElement[] extended = new StackTraceElement[callSiteStackTrace.length + 1];
        System.arraycopy(callSiteStackTrace, 0, extended, 1, callSiteStackTrace.length);
        extended[0] = new StackTraceElement("call site", "", null, 0);
        setStackTrace(extended);
    }

    public String getRemoteExceptionName() {
        return remoteExceptionName;
    }

    public String getRemoteMessage() {
        return remoteMessage;
    }
}
