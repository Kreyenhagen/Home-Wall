package com.kreyenhagen.homewall.ui.components

import java.util.concurrent.atomic.AtomicLong

/** A snackbar message. Each one has its own id, so the same text twice still shows twice. */
data class UserMessage(val text: String, val haptic: Boolean = false, val id: Long = nextMessageId.incrementAndGet())

private val nextMessageId = AtomicLong()
