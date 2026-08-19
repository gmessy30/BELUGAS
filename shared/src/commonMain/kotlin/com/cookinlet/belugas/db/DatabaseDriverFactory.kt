package com.cookinlet.belugas.db

import app.cash.sqldelight.db.SqlDriver

expect class DatabaseDriverFactory() {
    fun createDriver(): SqlDriver
}

fun createDatabase(driverFactory: DatabaseDriverFactory): BelugaDatabase {
    val driver = driverFactory.createDriver()
    return BelugaDatabase(driver)
}
