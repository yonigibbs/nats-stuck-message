package org.example

import io.nats.client.JetStream
import io.nats.client.JetStreamManagement
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.*
import java.time.Duration

private const val STREAM_NAME = "TEST-STREAM"
private const val MIRROR_STREAM_NAME = "$STREAM_NAME-MIRROR"
private const val CONSUMER_SUBJECT = "test.stream.foo"
private const val CONSUMER_NAME = "$STREAM_NAME-C"

fun main(args: Array<String>) {
    log("Application starting...")

    val errorMode = !args.contains("--no-err")
    log("Error mode = $errorMode")

    Nats.connect(
        Options.builder()
            .server("localhost")
            .userInfo("nats", "nats")
            .build()
    ).use { natsConnection ->
        val jetStreamManagement = natsConnection.jetStreamManagement()
        val jetStream = natsConnection.jetStream()
        if (!jetStreamManagement.streamNames.contains(STREAM_NAME))
            createAndPopulateStream(jetStreamManagement, jetStream)

        // NatsConsumer2(natsConnection, STREAM_NAME, CONSUMER_NAME, CONSUMER_SUBJECT).use { natsConsumer ->
        NatsConsumer(jetStream, STREAM_NAME, CONSUMER_NAME, CONSUMER_SUBJECT).use { natsConsumer ->
            natsConsumer.start()

            Thread.sleep(if (errorMode) 3_000 else Long.MAX_VALUE)
        }
    }
    log("Application ended")
}

private fun createAndPopulateStream(jetStreamManagement: JetStreamManagement, jetStream: JetStream) {
    // Create the mirror
    jetStreamManagement.addStream(
        StreamConfiguration.builder()
            .name(MIRROR_STREAM_NAME)
            .subjects("test.stream.>")
            .retentionPolicy(RetentionPolicy.Limits)
            .maxAge(Duration.ofDays(1))
            .replicas(1)
            .storageType(StorageType.File)
            .discardPolicy(DiscardPolicy.Old)
            .allowDirect(true)
            .build()
    )

    // Create the main stream
    jetStreamManagement.addStream(
        StreamConfiguration.builder()
            .name(STREAM_NAME)
            .retentionPolicy(RetentionPolicy.WorkQueue)
            .replicas(1)
            .storageType(StorageType.File)
            .discardPolicy(DiscardPolicy.Old)
            .allowDirect(true)
            .mirrorDirect(true)
            .mirror(
                Mirror.builder()
                    .name(MIRROR_STREAM_NAME)
                    .build(),
            )
            .build()
    )

    // Publish messages to the stream
    repeat(100) { jetStream.publish(CONSUMER_SUBJECT, it.toString().toByteArray()) }
}

