package com.russianranger.fta.runtime

import java.io.File

/**
 * Disposable NORMAL startup. Its original missing-map exit precedes every world listener.
 * ProcessPriority.h names its priority option "ProcessPriority", not "HighPriority".
 * World startup defaults that option to true and attempts setpriority(-15) before
 * database loading. Ordinary Android scheduling avoids its permission-denied ERROR;
 * UseProcessors=0 also leaves processor affinity to the operating system.
 */
internal object WorldUpdaterConfig {
    fun render(socket: File, client: File, sourceDirectory: File, dataDirectory: File,
        tempDirectory: File, log: File, user: String, password: String): String {
        listOf(socket, client, sourceDirectory, dataDirectory, tempDirectory, log).forEach {
            require(it.isAbsolute && it.path.none { char -> char in "\";\r\n\u0000" }) { "Unsafe private world configuration path" }
        }
        require(user.matches(Regex("[a-z_]{1,32}")) && password.matches(Regex("[a-f0-9]{48}")))
        return """
            [worldserver]
            LoginDatabaseInfo = ".;${socket.absolutePath};$user;$password;acore_auth"
            CharacterDatabaseInfo = ".;${socket.absolutePath};$user;$password;acore_characters"
            WorldDatabaseInfo = ".;${socket.absolutePath};$user;$password;acore_world"
            LoginDatabase.WorkerThreads = 1
            LoginDatabase.SynchThreads = 1
            CharacterDatabase.WorkerThreads = 1
            CharacterDatabase.SynchThreads = 1
            WorldDatabase.WorkerThreads = 1
            WorldDatabase.SynchThreads = 1
            Database.Reconnect.Seconds = 1
            Database.Reconnect.Attempts = 2
            RealmID = 1
            ProcessPriority = 0
            UseProcessors = 0
            SourceDirectory = "${sourceDirectory.absolutePath}"
            DataDir = "${dataDirectory.absolutePath}"
            MySQLExecutable = "${client.absolutePath}"
            TempDir = "${tempDirectory.absolutePath}"
            Updates.EnableDatabases = 7
            Updates.AutoSetup = 1
            Updates.Redundancy = 1
            Updates.ArchivedRedundancy = 0
            Updates.AllowRehash = 1
            Updates.CleanDeadRefMaxCount = 3
            Updates.ExceptionShutdownDelay = 0
            BindIP = "127.0.0.1"
            WorldServerPort = 8085
            Console.Enable = 0
            Ra.Enable = 0
            SOAP.Enabled = 0
            Cluster.Enabled = 0
            Network.UseSocketActivation = 0
            Metric.Enable = 0
            Log.Async.Enable = 0
            Appender.Console = 1,4,6
            Appender.Gate = 2,4,6,"${log.absolutePath}",w
            Logger.root = 4,Console Gate
        """.trimIndent() + "\n"
    }
}
