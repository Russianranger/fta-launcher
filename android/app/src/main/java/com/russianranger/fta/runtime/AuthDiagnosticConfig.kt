package com.russianranger.fta.runtime

import java.io.File

/** Original updater remains enabled. Invalid port is a second barrier against a listener. */
object AuthDiagnosticConfig {
    fun render(socket: File, client: File, sourceDirectory: File, tempDirectory: File, user: String, password: String): String {
        listOf(socket, client, sourceDirectory, tempDirectory).forEach { path ->
            require(path.isAbsolute && path.path.none { it in "\";\r\n\u0000" }) { "Unsafe private auth configuration path" }
        }
        // Generated local roles include UUID hexadecimal digits; delimiters remain forbidden.
        require(user.matches(Regex("[a-z_][a-z0-9_]{0,31}")))
        require(password.matches(Regex("[a-f0-9]{48}")))
        return """
            [authserver]
            LoginDatabaseInfo = ".;${socket.absolutePath};$user;$password;acore_auth"
            LoginDatabase.WorkerThreads = 1
            LoginDatabase.SynchThreads = 1
            Database.Reconnect.Seconds = 1
            Database.Reconnect.Attempts = 2
            SourceDirectory = "${sourceDirectory.absolutePath}"
            MySQLExecutable = "${client.absolutePath}"
            TempDir = "${tempDirectory.absolutePath}"
            Updates.EnableDatabases = 1
            Updates.AutoSetup = 1
            Updates.Redundancy = 1
            Updates.ArchivedRedundancy = 0
            Updates.AllowRehash = 1
            Updates.CleanDeadRefMaxCount = 3
            BindIP = "127.0.0.1"
            RealmServerPort = -1
            Network.UseSocketActivation = 0
            Appender.Console = 1,4,0
            Logger.root = 4,Console
        """.trimIndent() + "\n"
    }
}
