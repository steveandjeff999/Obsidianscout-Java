package com.obsidianscout.db

import java.util.ArrayDeque

/**
 * Thread-local stack registry for post-commit actions.
 * Guarantees that cache invalidation and Pub/Sub broadcasts execute strictly
 * after a transaction commits, preventing duplicate or premature invalidations
 * during CockroachDB serialization retry loops (40001).
 *
 * Uses a stack to safely handle nested transactions without wiping outer scopes.
 */
object PostCommitHook {
    private val localStack = ThreadLocal<ArrayDeque<MutableList<() -> Unit>>>()

    /**
     * Schedules [action] to run immediately after the current write transaction successfully commits.
     * If not currently inside a tracked write transaction, runs [action] immediately.
     */
    fun afterCommit(action: () -> Unit) {
        val stack = localStack.get()
        val currentList = stack?.peekLast()
        if (currentList != null) {
            currentList.add(action)
        } else {
            action()
        }
    }

    internal fun pushScope(): MutableList<() -> Unit> {
        var stack = localStack.get()
        if (stack == null) {
            stack = ArrayDeque()
            localStack.set(stack)
        }
        val list = mutableListOf<() -> Unit>()
        stack.addLast(list)
        return list
    }

    internal fun popScope() {
        val stack = localStack.get()
        if (stack != null) {
            stack.pollLast()
            if (stack.isEmpty()) {
                localStack.remove()
            }
        }
    }
}
