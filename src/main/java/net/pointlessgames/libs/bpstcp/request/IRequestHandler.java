package net.pointlessgames.libs.bpstcp.request;

public interface IRequestHandler {
    <T> ResponseHandling respond(IRequestResolver requestResolver, IRequest<T> request) throws Exception;

    interface IRequestResolver {
        <T> ResponseHandling resolve(IRequest<T> request, T response);
    }

    enum ResponseHandling {
        HANDLED, WILL_HANDLE, NOT_HANDLED
    }
}
