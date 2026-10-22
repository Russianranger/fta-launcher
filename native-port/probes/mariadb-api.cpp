// SPDX-License-Identifier: MIT
// Independently written C API probe. Does not connect to a database or import SQL.
#include <mysql.h>
#include <cstdio>

#if !defined(MARIADB_BASE_VERSION) || !defined(MARIADB_PACKAGE_VERSION_ID)
#error This probe requires actual MariaDB Connector/C headers.
#endif

int main()
{
    unsigned int compatibilityVersion = 0;
    if (mariadb_get_infov(nullptr, MARIADB_CLIENT_VERSION_ID, &compatibilityVersion))
        return 1;
    std::printf("header_mysql_version_id=%lu\nheader_connector_version_id=%lu\n"
                "loaded_client_version_id=%lu\nloaded_compatibility_version_id=%u\n"
                "loaded_client_version=%s\nthread_safe=%u\n",
                static_cast<unsigned long>(MYSQL_VERSION_ID),
                static_cast<unsigned long>(MARIADB_PACKAGE_VERSION_ID),
                mysql_get_client_version(), compatibilityVersion, mysql_get_client_info(),
                static_cast<unsigned>(mysql_thread_safe()));
    if (mysql_get_client_version() != MARIADB_PACKAGE_VERSION_ID || !mysql_thread_safe())
        return 2;
    MYSQL* handle = mysql_init(nullptr);
    if (!handle)
        return 3;
    const my_bool enforceTls = 1;
    const int optionResult = mysql_optionsv(handle, MYSQL_OPT_SSL_ENFORCE, &enforceTls);
    mysql_close(handle);
    std::printf("tls_enforcement_option_accepted=%u\n", optionResult == 0);
    return optionResult == 0 ? 0 : 4;
}
