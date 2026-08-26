package gov.nasa.jpl.parakeet.general.reporting

import gov.nasa.jpl.parakeet.foundation.reporting.ChannelReport
import gov.nasa.jpl.parakeet.foundation.reporting.ChannelizedReportHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// TODO: Since this performs so much better than ParallelReportHandler,
//   consider removing ParallelReportHandler entirely.
/**
 * Like [ParallelReportHandler], runs the report handler on the IO dispatcher,
 * in parallel with the main thread.
 * However, to reduce the overhead of adding reports to the cross-thread communication channel,
 * this handler buffers reports and sends them in batches.
 */
class BufferedParallelReportHandler(
    scope: CoroutineScope,
    private val handler: ChannelizedReportHandler,
    private val batchSize: Int = 1000,
    channelCapacity: Int = 62,
) : ChannelizedReportHandler, AutoCloseable {
    init {
        require(channelCapacity >= 1) { "Channel capacity must be at least 1" }
    }

    private val channel = Channel<MutableList<Any?>>(channelCapacity)
    private val job = scope.launch(Dispatchers.IO) {
        for (batch in channel) {
            for (report in batch) {
                handler(report)
            }
            // Clear the batch immediately to free up memory sooner
            batch.clear()
        }
    }

    // Prepare the batch pool with pre-allocated array lists to minimize reallocations.
    // We need the full channel capacity, plus one for the receiver, plus one for the sender,
    // to make sure there's never any conflict or race conditions.
    private val batchPool = Array(channelCapacity + 2) { ArrayList<Any?>(batchSize) }
    private var currentBatchIndex = 0
    private val currentBatch get() = batchPool[currentBatchIndex]

    // Unlike most ChannelizedReportHandlers, this defers the two specialized methods to the general report,
    // rather than splitting report into two specialized handlers.
    override fun <T> initChannel(metadata: ChannelReport.ChannelMetadata<T>) {
        this(metadata)
    }

    override fun <T> report(data: ChannelReport.ChannelData<T>) {
        this(data)
    }

    override fun invoke(p1: Any?) {
        currentBatch += p1
        if (currentBatch.size >= batchSize) {
            runBlocking {
                // Send this batch over the channel.
                channel.send(currentBatch)
                // Advance to the next batch in the pool
                currentBatchIndex = (currentBatchIndex + 1) % batchPool.size
                // The receiver is responsible for clearing batches, so we don't need to do it here.
            }
        }
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
        fun <R> ChannelizedReportHandler.inParallelBatches(batchSize: Int = 1000, channelCapacity: Int = 62, block: (BufferedParallelReportHandler) -> R) = runBlocking {
            BufferedParallelReportHandler(contextOf<CoroutineScope>(), this@inParallelBatches, batchSize, channelCapacity).use(block)
        }
    }
}