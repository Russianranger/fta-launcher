// SPDX-License-Identifier: MIT
// Minimal reproduction of the original core's MySQL-specific API usage.
// Compile-only probe; this program must never be executed.
#include <mysql.h>

int original_api_probe(MYSQL* mysql, MYSQL_STMT* statement, MYSQL_BIND* bindings)
{
    mysql_ssl_mode mode = SSL_MODE_REQUIRED;
    int result = mysql_options(mysql, MYSQL_OPT_SSL_MODE, &mode);
#if MYSQL_VERSION_ID >= 80300
    result += mysql_stmt_bind_named_param(statement, bindings, 0, nullptr);
#else
    result += mysql_stmt_bind_param(statement, bindings);
#endif
    return result;
}
