package org.example

import io.nats.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

private const val NUM_WORKERS = 4

class NatsConsumer(
    private val jetStream: JetStream,
    private val streamName: String,
    private val consumerName: String,
    private val consumerSubject: String
) : AutoCloseable {
    private val threadPool = Executors.newFixedThreadPool(
        // Add an extra thread for the poller
        NUM_WORKERS + 1,
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
    private lateinit var jetStreamSubscription: JetStreamSubscription
    private lateinit var workerJobs: List<Job>
    private lateinit var pollerJob: Job
    private val channel = Channel<Message>(NUM_WORKERS)

    fun start() {
        log("Starting NATS consumers...")
        jetStreamSubscription = jetStream.subscribe(
            consumerSubject,
            PullSubscribeOptions.builder()
                .stream(streamName)
                .durable(consumerName)
                .build(),
        )
        scope.launch {
            pollerJob = scope.launch {
                while (isActive) {
                    try {
                        val message = try {
                            jetStreamSubscription.fetch(1, Duration.ofSeconds(5)).singleOrNull()
                        } catch (e: IllegalStateException) {
                            log("JetStream subscription became inactive")
                            throw CancellationException()
                        } catch (e: Throwable) {
                            log("Error fetching message from JetStream:\n$e")
                            throw e
                        }
                        if (message != null) {
                            log(
                                "Fetched message ${message.metaData().streamSequence()} from NATS " +
                                    "(deliveredCount=${message.metaData().deliveredCount()}). Sending to channel."
                            )
                            channel.send(message)
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

            workerJobs = (1..NUM_WORKERS).map {
                log("Starting NATS consumer $it...")
                scope.launch {
                    while (isActive) {
                        try {
                            val message = channel.receive()
                            log(
                                "Processing message ${message.metaData().streamSequence()} from NATS " +
                                    "(deliveredCount=${message.metaData().deliveredCount()})."
                            )

                            // Simulate processing taking a bit of time
                            delay(1_500)

                            message.ack()

                            log("Acknowledged message ${message.metaData().streamSequence()} from NATS.")
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
                    if (::jetStreamSubscription.isInitialized) jetStreamSubscription.cleanUnsubscribe()
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

private fun Subscription.cleanUnsubscribe() {
    try {
        if (isActive) unsubscribe()
    } catch (e: IllegalStateException) {
        // Ignore: this subscription has already been unsubscribed.
    } catch (e: Throwable) {
        // Log and continue.
        log("Error unsubscribing from NATS:\n$e")
    }
}
