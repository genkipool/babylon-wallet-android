package rdx.works.peerdroid.domain

/**
 * Sending a message failed before anything was handed to the transport (e.g. there is no open data channel or
 * iroh session for the connector), so the connector extension cannot have received it and it is safe to send it
 * over another transport.
 *
 * Any other send failure means the message may have been delivered and must not be sent again.
 */
class MessageNotSentException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
