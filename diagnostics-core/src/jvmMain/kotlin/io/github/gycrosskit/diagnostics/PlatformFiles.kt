package io.github.gycrosskit.diagnostics

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal actual fun platformMove(source: Path, destination: Path) = SystemFileSystem.atomicMove(source, destination)
