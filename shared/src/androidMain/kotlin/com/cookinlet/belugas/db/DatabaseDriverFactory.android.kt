package com.cookinlet.belugas.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.cookinlet.belugas.androidContext

actual class DatabaseDriverFactory actual constructor() {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(BelugaDatabase.Schema, androidContext, "belugas.db")
    }
}
