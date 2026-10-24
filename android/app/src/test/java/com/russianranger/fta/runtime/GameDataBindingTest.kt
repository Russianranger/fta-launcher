package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test

class GameDataBindingTest {
    @Test fun worldVerificationAndStartupUseVisiblePriorityButScreenOffHealthDoesNot() {
        var binds = 0; var unbinds = 0
        val binding = GameDataBinding({ binds++; true }, { unbinds++ })
        binding.update(true, true, "world-service", "WORLD_ASSET_VERIFY")
        binding.update(false, true, "world-service", "WORLD_ASSET_VERIFY")
        binding.update(true, true, "world-service", "WORLD_SESSION_START")
        binding.update(true, true, "world-service", "WORLD_SERVICE_RUNNING")
        assertEquals(2, binds); assertEquals(2, unbinds)
    }
    @Test fun visibleImportBindsOnceAndCompletionReleasesWithoutRestartingAnything() {
        var binds = 0; var unbinds = 0
        val binding = GameDataBinding({ binds++; true }, { unbinds++ })
        for (stage in listOf("CHECKING", "GAME_DATA_COPY", "GAME_DATA_COPY_SYNC", "GAME_DATA_ARCHIVE_INDEX", "GAME_DATA_EXTRACT", "GAME_DATA_PREFLIGHT", "GAME_DATA_PUBLISH"))
            binding.update(true, true, "game-data", stage)
        assertEquals(1, binds); assertEquals(0, unbinds)
        binding.update(true, true, "game-data", "GAME_DATA_PREFLIGHT_PASSED")
        binding.release(); assertEquals(1, unbinds)
    }
    @Test fun hiddenActivityIdleWorkerAndOtherDiagnosticsNeverAcquirePriority() {
        var binds = 0
        val binding = GameDataBinding({ binds++; true }, {})
        binding.update(false, true, "game-data", "GAME_DATA_COPY")
        binding.update(true, false, "game-data", "GAME_DATA_COPY")
        binding.update(true, true, "local-service", "CHECKING")
        binding.update(true, true, "game-data", "FAILED")
        binding.update(true, true, "game-data", null)
        assertEquals(0, binds)
    }
    @Test fun backgroundReleasesAndReturningToActiveVerificationReacquires() {
        var binds = 0; var unbinds = 0
        val binding = GameDataBinding({ binds++; true }, { unbinds++ })
        binding.update(true, true, "game-data", "GAME_DATA_VERIFY")
        binding.update(false, true, "game-data", "GAME_DATA_VERIFY")
        assertEquals(1, unbinds)
        binding.update(true, true, "game-data", "GAME_DATA_VERIFY")
        assertEquals(2, binds)
        binding.update(true, false, "game-data", "GAME_DATA_VERIFY")
        assertEquals(2, unbinds)
    }
    @Test fun failedAndThrowingBindAreBalancedAndCanRetryOnlyWhenWorkIsObserved() {
        for (throws in listOf(false, true)) {
            var binds = 0; var unbinds = 0
            val binding = GameDataBinding({ binds++; if (throws) error("bind failure") else false }, { unbinds++ })
            binding.update(true, true, "game-data", "GAME_DATA_COPY")
            binding.release(); assertEquals(1, unbinds)
            binding.update(true, false, "game-data", "GAME_DATA_COPY")
            assertEquals(1, binds)
        }
    }
    @Test fun terminalFailureStopAndRejectionAllReleaseTheVisibleConnection() {
        for (stage in listOf("FAILED", "STOPPED", "GAME_DATA_REJECTED")) {
            var released = 0
            val binding = GameDataBinding({ true }, { released++ })
            binding.update(true, true, "game-data", "GAME_DATA_COPY")
            binding.update(true, true, "game-data", stage)
            binding.release(); assertEquals(1, released)
        }
    }
}
