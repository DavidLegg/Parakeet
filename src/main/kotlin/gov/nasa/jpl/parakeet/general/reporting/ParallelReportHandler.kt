package gov.nasa.jpl.parakeet.general.reporting

import gov.nasa.jpl.parakeet.foundation.reporting.ChannelReport
import gov.nasa.jpl.parakeet.foundation.reporting.ChannelizedReportHandler
import gov.nasa.jpl.parakeet.kernel.ReportHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.AutoCloseable

/**
 * Runs the report handler on the IO dispatcher, in parallel with the main thread.
 */
class ParallelReportHandler private constructor(
    scope: CoroutineScope,
    private val handler: ChannelizedReportHandler,
    bufferCapacity: Int = Channel.BUFFERED,
) : ChannelizedReportHandler, AutoCloseable {
    private val channel = Channel<Any?>(bufferCapacity)
    private val job = scope.launch(Dispatchers.IO) {
        for (report in channel) {
            handler(report)
        }
    }

    // Unlike most ChannelizedReportHandlers, this defers the two specialized methods to the general report,
    // rather than splitting report into two specialized handlers.
    override fun <T> initChannel(metadata: ChannelReport.ChannelMetadata<T>) {
        this(metadata)
    }

    override fun <T> report(data: ChannelReport.ChannelData<T>) {
        this(data)
    }

    override fun invoke(p1: Any?) = runBlocking {
        channel.send(p1)
    }

    override fun close() {
        runBlocking {
            // Close the channel to signal end-of-data to the reporter
            channel.close()
            // Join the reporter to await it reporting all remaining data in the channel
            job.join()
        }
    }

    companion object {
        /**
         * Run this [ChannelizedReportHandler] on a separate thread, in parallel with the simulator.
         */
        fun <R> ChannelizedReportHandler.inParallel(bufferCapacity: Int = Channel.BUFFERED, block: (ParallelReportHandler) -> R) = runBlocking {
            ParallelReportHandler(contextOf<CoroutineScope>(), this@inParallel, bufferCapacity).use(block)
        }
    }
}
