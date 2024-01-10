package org.example

import java.time.LocalTime

fun log(msg: String) = println("${LocalTime.now()} [${Thread.currentThread().name}] :: $msg")