package io.github.rustsupport.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CargoInvocationTest {
    @Test
    fun `arguments of a run with a binary, features and program arguments`() {
        val invocation = CargoInvocation(
            listOf("run"), packageName = "app", targetKind = CargoTargetKind.BIN, targetName = "server", features = "tls,metrics",
            noDefaultFeatures = true, cargoArguments = listOf("--release"), programArguments = listOf("--port", "8080"),
        )
        assertEquals(
            listOf("run", "--color=always", "--package", "app", "--bin", "server", "--features", "tls,metrics", "--no-default-features", "--release", "--", "--port", "8080"),
            invocation.arguments(),
        )
    }

    @Test
    fun `no color for commands of other tools and no separator without program arguments`() {
        assertEquals(listOf("nextest", "run", "--lib"), CargoInvocation(listOf("nextest", "run"), targetKind = CargoTargetKind.LIB).arguments())
    }

    @Test
    fun `a target kind with a name is skipped while the name is empty`() {
        assertEquals(listOf("build"), CargoInvocation(listOf("build"), targetKind = CargoTargetKind.TEST, targetName = " ").arguments(color = false))
    }

    @Test
    fun `debug build of run is a build of the same target`() {
        val invocation = CargoInvocation(listOf("run"), packageName = "app", targetKind = CargoTargetKind.BIN, targetName = "app", programArguments = listOf("x"))
        assertEquals(listOf("build", "--package", "app", "--bin", "app"), invocation.debugBuildArguments())
    }

    @Test
    fun `debug build of tests does not run them`() {
        val invocation = CargoInvocation(listOf("test"), packageName = "app", targetKind = CargoTargetKind.LIB, programArguments = listOf("tests::a", "--exact"))
        assertEquals(listOf("test", "--package", "app", "--lib", "--no-run"), invocation.debugBuildArguments())
        assertEquals(listOf("bench", "--no-run"), CargoInvocation(listOf("bench")).debugBuildArguments())
    }

    @Test
    fun `nothing to debug for check, clippy and build`() {
        for (command in listOf("check", "clippy", "build", "doc")) assertNull(CargoInvocation(listOf(command)).debugBuildArguments())
    }

    @Test
    fun `parses the cargo arguments of a runnable`() {
        val invocation = CargoInvocation.parse(listOf("test", "--package", "app", "--lib"), listOf("tests::it_works", "--exact", "--show-output"))
        assertEquals(CargoInvocation(listOf("test"), packageName = "app", targetKind = CargoTargetKind.LIB, programArguments = listOf("tests::it_works", "--exact", "--show-output")), invocation)
        assertTrue(invocation.isTestHarness)
    }

    @Test
    fun `parses targets, features, flags with values and the separator`() {
        val invocation = CargoInvocation.parse(listOf("run", "-p", "app", "--bin=srv", "--features", "a", "-F", "b", "--all-features", "--target", "x86_64-pc-windows-gnu", "--release", "--", "-v"))
        assertEquals("app", invocation.packageName)
        assertEquals(CargoTargetKind.BIN, invocation.targetKind)
        assertEquals("srv", invocation.targetName)
        assertEquals("a,b", invocation.features)
        assertTrue(invocation.allFeatures)
        assertEquals(listOf("--target", "x86_64-pc-windows-gnu", "--release"), invocation.cargoArguments)
        assertEquals(listOf("-v"), invocation.programArguments)
    }

    @Test
    fun `parse and arguments round trip`() {
        val args = listOf("test", "--package", "app", "--test", "integration", "--", "it_works", "--exact", "--show-output")
        assertEquals(args, CargoInvocation.parse(args).arguments(color = false))
    }

    @Test
    fun `rerun keeps the flags of the harness and replaces the filters`() {
        assertEquals(
            listOf("a::b", "c", "--exact", "--nocapture", "--test-threads", "1"),
            CargoTestArguments.rerun(listOf("tests::", "--nocapture", "--test-threads", "1", "--exact"), listOf("a::b", "c")),
        )
    }

    @Test
    fun `cargo test shows the output of the passing tests unless told otherwise`() {
        assertEquals(listOf("test", "--lib", "--", "--show-output"), CargoInvocation(listOf("test"), targetKind = CargoTargetKind.LIB).arguments(color = false))
        assertEquals(listOf("a::", "--show-output"), CargoInvocation(listOf("test"), programArguments = listOf("a::")).harnessArguments())
        assertEquals(listOf("a::", "--nocapture"), CargoInvocation(listOf("test"), programArguments = listOf("a::", "--nocapture")).harnessArguments())
        assertEquals(listOf("a", "--exact", "--show-output"), CargoInvocation(listOf("test"), programArguments = listOf("a", "--exact", "--show-output")).harnessArguments())
        assertEquals(listOf("bench"), CargoInvocation(listOf("bench")).arguments(color = false))
    }
}
