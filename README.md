# Attempt to reproduce stuck NATS messages (w/ a kotlin client)

## Description of the bug and the reproduction program

### Description of the bug

A message sent to a work queue stream with an AckWait of 30s becomes _stuck_ in
its consumer in a state where it is never redelivered. It looks like the
consumer erroneously moves the ack floor beyond the stuck message's sequence
number. We have seen two scenarios play out when this condition is triggered:

1. the message is never sent to the consumer at all
2. the message is sent but has been NAKed as part of a worker shutdown/teardown
   procedure

Once the message gets into this state, it cannot be delivered until the
underlying durable consumer is deleted and recreated.


### Description of reproduction program

The reproduction is a Kotlin program using coroutines (this may be relevant).
On first startup, a test stream is created (with a work queue mirror) and
populated with a hundred dummy messages. The program then runs worker coroutines
which read from the work queue stream, delay for a while to simulate processing
time, then ack the `Message`s.

The program runs in "exploding banana" mode by default; unless `--no-err` is
passed, we will cancel the root coroutines and tear down the process after a
few seconds of process run time, triggering a `CancellationException` to be
sent to the active worker coroutines. This will `nak` any messages being
processed. Running the program this way in a loop usually causes the bug to be
triggered within a few minutes.


## Reproduction instructions

1. Run `./build.sh` to gradle-build the code.

2. Run `./exec.sh`, which:
  - brings up a fresh containerized nats with `docker-compose`
  - runs this test program in "exploding banana" mode in a loop
3. Leave the test code running in a loop for a few minutes, and then kill with `C-c`. 
4. Run `./exec-no-err.sh` to run the consumer in "normal" mode and clear any
   messages from the queue.

## Description of triggered state

The triggered state looks like this:

```
[greg@MacBook-Pro:~/git/nats-stuck-message] [main *]  [ 3:06pm]
λ nats stream ls
╭────────────────────────────────────────────────────────────────────────────────────────────╮
│                                           Streams                                          │
├────────────────────┬─────────────┬─────────────────────┬──────────┬─────────┬──────────────┤
│ Name               │ Description │ Created             │ Messages │ Size    │ Last Message │
├────────────────────┼─────────────┼─────────────────────┼──────────┼─────────┼──────────────┤
│ TEST-STREAM        │             │ 2024-01-10 15:03:54 │ 1        │ 46 B    │ 3m5s         │
│ TEST-STREAM-MIRROR │             │ 2024-01-10 15:03:54 │ 100      │ 4.6 KiB │ 3m5s         │
╰────────────────────┴─────────────┴─────────────────────┴──────────┴─────────┴──────────────╯
```

One stuck message on the stream...


```
[greg@MacBook-Pro:~/git/nats-stuck-message] [main *]  [ 3:08pm]
λ nats consumer info TEST-STREAM TEST-STREAM-C
Information for Consumer TEST-STREAM > TEST-STREAM-C created 2024-01-10T15:03:54-05:00

Configuration:

                    Name: TEST-STREAM-C
               Pull Mode: true
          Filter Subject: test.stream.foo
          Deliver Policy: All
              Ack Policy: Explicit
                Ack Wait: 30.00s
           Replay Policy: Instant
         Max Ack Pending: 1,000
       Max Waiting Pulls: 512

State:

  Last Delivered Message: Consumer sequence: 234 Stream sequence: 100 Last delivery: 1m53s ago
    Acknowledgment Floor: Consumer sequence: 234 Stream sequence: 100 Last Ack: 1m50s ago
        Outstanding Acks: 0 out of maximum 1,000
    Redelivered Messages: 0
    Unprocessed Messages: 0
           Waiting Pulls: 0 of maximum 512
```

Strangely, this message isn't on the waiting pulls list like we've seen before.
Running the program in normal mode and attempting to clear the consumer does
nothing (and n.b. last ack 1m53s ago is well beyond our 30 second ack wait):

```
[greg@MacBook-Pro:~/git/nats-stuck-message] [main *]  [ 3:09pm]
λ ./exec-no-err.sh
~/git/nats-stuck-message ~/git/nats-stuck-message
15:09:31.141834 [main] :: Application starting...
15:09:31.189498 [main] :: Error mode = false
15:09:31.347570 [main] :: Starting NATS consumers...
15:09:31.423130 [nats-consumer-1] :: Starting NATS consumer 1...
15:09:31.423827 [nats-consumer-1] :: Starting NATS consumer 2...
15:09:31.424428 [nats-consumer-1] :: Starting NATS consumer 3...
15:09:31.425280 [nats-consumer-1] :: Starting NATS consumer 4...
^C%
```

Deleting the consumer and re-running the program (recreating the consumer)
causes the message to be cleared.

```
[greg@MacBook-Pro:~/git/nats-stuck-message] [main *]  [ 3:09pm]
λ nats consumer rm
? Select a Stream TEST-STREAM
? Select a Consumer TEST-STREAM-C
? Really delete Consumer TEST-STREAM > TEST-STREAM-C Yes
[greg@MacBook-Pro:~/git/nats-stuck-message] [main *]  [ 3:09pm]
λ ./exec-no-err.sh
~/git/nats-stuck-message ~/git/nats-stuck-message
15:09:44.503528 [main] :: Application starting...
15:09:44.551531 [main] :: Error mode = false
15:09:44.711534 [main] :: Starting NATS consumers...
15:09:44.794927 [nats-consumer-1] :: Starting NATS consumer 1...
15:09:44.796733 [nats-consumer-1] :: Starting NATS consumer 2...
15:09:44.797566 [nats-consumer-1] :: Starting NATS consumer 3...
15:09:44.797813 [nats-consumer-1] :: Starting NATS consumer 4...
15:09:44.806028 [nats-consumer-2] :: Fetched message 10 from NATS (deliveredCount=1). Sending to channel.
15:09:44.807297 [nats-consumer-4] :: Processing message 10 from NATS (deliveredCount=1).
15:09:46.322742 [nats-consumer-1] :: Acknowledged message 10 from NATS.
```

## Hardware info

We hit this bug running M2 macs:

```
[greg@MacBook-Pro:~/git/nats-stuck-message] [main *]  [ 3:09pm]
λ uname -a
Darwin MacBook-Pro.lan 23.1.0 Darwin Kernel Version 23.1.0: Mon Oct  9 21:28:45 PDT 2023; root:xnu-10002.41.9~6/RELEASE_ARM64_T6020 arm64
```

We have verified the reproduction on two different laptops. We have not tried to reproduce it on Linux/amd64.

