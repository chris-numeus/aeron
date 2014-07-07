package uk.co.real_logic.aeron.conductor;

import uk.co.real_logic.aeron.util.ErrorCode;
import uk.co.real_logic.aeron.util.command.CorrelatedMessageFlyweight;
import uk.co.real_logic.aeron.util.command.LogBuffersMessageFlyweight;
import uk.co.real_logic.aeron.util.command.PublicationMessageFlyweight;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.util.concurrent.broadcast.CopyBroadcastReceiver;
import uk.co.real_logic.aeron.util.event.EventLogger;
import uk.co.real_logic.aeron.util.protocol.ErrorFlyweight;

import static uk.co.real_logic.aeron.util.command.ControlProtocolEvents.*;

/**
 * Analogue of {@see MediaDriverProxy} on the receive side
 */
public class MediaDriverReceiver
{
    private static final EventLogger LOGGER = new EventLogger(MediaDriverReceiver.class);

    private final CopyBroadcastReceiver broadcastReceiver;

    private final PublicationMessageFlyweight publicationMessage = new PublicationMessageFlyweight();
    private final ErrorFlyweight errorHeader = new ErrorFlyweight();
    private final LogBuffersMessageFlyweight logBuffersMessage = new LogBuffersMessageFlyweight();
    private final CorrelatedMessageFlyweight correlatedMessage = new CorrelatedMessageFlyweight();

    public MediaDriverReceiver(final CopyBroadcastReceiver broadcastReceiver)
    {
        this.broadcastReceiver = broadcastReceiver;
    }

    public int receive(final MediaDriverListener listener, final long activeCorrelationId)
    {
        return broadcastReceiver.receive(
            (msgTypeId, buffer, index, length) ->
            {
                try
                {
                    switch (msgTypeId)
                    {
                        case ON_NEW_CONNECTED_SUBSCRIPTION:
                        case ON_NEW_PUBLICATION:
                            logBuffersMessage.wrap(buffer, index);

                            final String destination = logBuffersMessage.destination();

                            final long sessionId = logBuffersMessage.sessionId();
                            final long channelId = logBuffersMessage.channelId();
                            final long termId = logBuffersMessage.termId();
                            final int positionIndicatorId = logBuffersMessage.positionCounterId();

                            if (msgTypeId == ON_NEW_PUBLICATION)
                            {
                                if (logBuffersMessage.correlationId() != activeCorrelationId)
                                {
                                    break;
                                }

                                listener.onNewPublication(destination, sessionId, channelId, termId, positionIndicatorId, logBuffersMessage);
                            }
                            else
                            {
                                listener.onNewConnectedSubscription(destination, sessionId, channelId, termId, logBuffersMessage);
                            }
                            break;

                        case OPERATION_SUCCEEDED:
                            correlatedMessage.wrap(buffer, index);
                            if (correlatedMessage.correlationId() == activeCorrelationId)
                            {
                                listener.operationSucceeded();
                            }

                        case ERROR_RESPONSE:
                            handleErrorResponse(buffer, index, listener, activeCorrelationId);
                            break;

                        default:
                            break;
                    }
                }
                catch (final Exception ex)
                {
                    LOGGER.logException(ex);
                }
            }
        );
    }

    private void handleErrorResponse(
            final AtomicBuffer buffer,
            final int index,
            final MediaDriverListener listener,
            final long activeCorrelationId)
    {
        errorHeader.wrap(buffer, index);
        final ErrorCode errorCode = errorHeader.errorCode();
        switch (errorCode)
        {
            // Publication errors
            case PUBLICATION_CHANNEL_ALREADY_EXISTS:
            case GENERIC_ERROR_MESSAGE:
            case INVALID_DESTINATION_IN_PUBLICATION:
            case PUBLICATION_CHANNEL_UNKNOWN:
                final long correlationId = correlationId(buffer, errorHeader.offendingHeaderOffset());
                if (correlationId == activeCorrelationId)
                {
                    listener.onError(errorCode, errorHeader.errorMessage());
                }
                break;

            default:
                // TODO
                break;
        }
    }

    private long correlationId(final AtomicBuffer buffer, final int offset)
    {
        publicationMessage.wrap(buffer, offset);
        return publicationMessage.correlationId();
    }

}
