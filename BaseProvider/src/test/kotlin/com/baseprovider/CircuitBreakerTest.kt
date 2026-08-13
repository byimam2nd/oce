package com.baseprovider

import com.baseprovider.network.HostCircuitBreaker
import org.junit.Assert.*
import org.junit.Test

class CircuitBreakerTest {

    @Test
    fun `isOpen returns false for unknown host`() {
        assertFalse(HostCircuitBreaker.isOpen("unknown.example.com"))
    }

    @Test
    fun `single failure does not open circuit before threshold`() {
        HostCircuitBreaker.reportFailure("single.example.com")
        assertFalse(HostCircuitBreaker.isOpen("single.example.com"))
    }

    @Test
    fun `three failures then isOpen returns true`() {
        HostCircuitBreaker.reportFailure("three.example.com")
        HostCircuitBreaker.reportFailure("three.example.com")
        HostCircuitBreaker.reportFailure("three.example.com")
        assertTrue(HostCircuitBreaker.isOpen("three.example.com"))
    }

    @Test
    fun `reportSuccess after failure closes circuit`() {
        HostCircuitBreaker.reportFailure("recover.example.com")
        HostCircuitBreaker.reportFailure("recover.example.com")
        HostCircuitBreaker.reportFailure("recover.example.com")
        assertTrue(HostCircuitBreaker.isOpen("recover.example.com"))
        HostCircuitBreaker.reportSuccess("recover.example.com")
        assertFalse(HostCircuitBreaker.isOpen("recover.example.com"))
    }
}