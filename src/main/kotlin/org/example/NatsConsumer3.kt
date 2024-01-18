package org.example

import io.nats.client.Connection
import io.nats.client.ConsumerContext
import io.nats.client.FetchConsumeOptions
import io.nats.client.api.ConsumerConfiguration
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

class NatsConsumer3(
    private val natsConnection: Connection,
    private val streamName: String,
    private val consumerName: String,
    private val consumerSubject: String
) : AutoCloseable {
    private val NUM_WORKERS = 4
    private val threadPool = Executors.newFixedThreadPool(
        NUM_WORKERS,
        // Give each thread a readable name, useful for logging purposes.
        object : ThreadFactory {
            private val threadCounter = AtomicLong()
            override fun newThread(r: Runnable): Thread =
                Executors.defaultThreadFactory().newThread(r)
                    .apply {
                        name = "nats-consumer-${threadCounter.incrementAndGet()}"
                    }
        }
    )
    private val dispatcher = threadPool.asCoroutineDispatcher()
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + supervisorJob)
    private val streamContext = natsConnection.getStreamContext(streamName)
    private lateinit var consumerContext: ConsumerContext
    private lateinit var workerJobs: List<Job>

    fun start() {
        log("Starting NATS consumers...")
        consumerContext = streamContext.createOrUpdateConsumer(
            ConsumerConfiguration.builder().durable(consumerName).build()
        )

        scope.launch {
            workerJobs = (1..NUM_WORKERS).map {
                log("Starting NATS consumer $it...")
                scope.launch {
                    while (isActive) {
                        try {
                            consumerContext.fetch(
                                FetchConsumeOptions.builder()
                                    .maxMessages(1)
                                    .expiresIn(5_000)
                                    .build()
                            ).use { fetchConsumer ->
                                val message = try {
                                    fetchConsumer.nextMessage()
                                } catch (e: IllegalStateException) {
                                    log("JetStream subscription became inactive")
                                    throw CancellationException()
                                } catch (e: Throwable) {
                                    log("Error fetching message from JetStream:\n$e")
                                    throw e
                                }

                                if (message != null) {
                                    log(
                                        "Processing message ${message.metaData().streamSequence()} from NATS " +
                                            "(deliveredCount=${message.metaData().deliveredCount()})."
                                    )

                                    // Simulate processing taking a bit of time∂
                                    delay(1_500)

                                    message.ack()

                                    log("Acknowledged message ${message.metaData().streamSequence()} from NATS.")
                                }
                            }
                        } catch (e: CancellationException) {
                            log("Coroutine cancelled. Quitting.")
                            supervisorJob.cancel()
                            break
                        } catch (e: Throwable) {
                            log("Caught exception. Shutting down NATS consumer:\n$e")
                            supervisorJob.cancel()
                            break
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        log("Closing NATS consumer...")
        try {
            runBlocking {
                async(Dispatchers.IO, CoroutineStart.DEFAULT) {
                    supervisorJob.cancel()
                    if (::workerJobs.isInitialized) workerJobs.joinAll()
                    log("All worker jobs shut down")
                }.await()
            }
        } catch (e: Throwable) {
            log("Error in closing NATS consumer:\n$e")
        }
        threadPool.shutdown()
        log("NATS consumer closed")
    }
}