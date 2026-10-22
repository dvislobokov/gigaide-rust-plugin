package io.github.rustsupport.cargo

import io.github.rustsupport.cargo.CargoNewProject.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CargoNewProjectTest {
    @Test
    fun `package names from project names`() {
        assertEquals("my-app", CargoNewProject.packageName("My App"))
        assertEquals("http_svc", CargoNewProject.packageName("http_svc"))
        assertEquals("a-b", CargoNewProject.packageName("a...b"))
        assertEquals("rust-2048", CargoNewProject.packageName("2048"))
        assertEquals("app", CargoNewProject.packageName("Проект"))
        assertEquals("test-app", CargoNewProject.packageName("test"))
        assertEquals("fn-app", CargoNewProject.packageName("fn"))
        assertEquals("app", CargoNewProject.packageName("  "))
    }

    @Test
    fun `names cargo refuses`() {
        assertNull(CargoNewProject.nameProblem("my-app_2"))
        assertNotNull(CargoNewProject.nameProblem(""))
        assertNotNull(CargoNewProject.nameProblem("my app"))
        assertNotNull(CargoNewProject.nameProblem("1app"))
        assertNotNull(CargoNewProject.nameProblem("struct"))
        assertNotNull(CargoNewProject.nameProblem("std"))
        assertNotNull(CargoNewProject.nameProblem("deps"))
    }

    @Test
    fun `cargo init arguments`() {
        assertEquals(
            listOf("init", "--name", "app", "--bin", "--vcs", "none"),
            CargoNewProject.arguments("app", Template.BINARY, "", git = false),
        )
        assertEquals(
            listOf("init", "--name", "lib", "--lib", "--edition", "2021", "--vcs", "git"),
            CargoNewProject.arguments("lib", Template.LIBRARY, "2021", git = true),
        )
    }
}
