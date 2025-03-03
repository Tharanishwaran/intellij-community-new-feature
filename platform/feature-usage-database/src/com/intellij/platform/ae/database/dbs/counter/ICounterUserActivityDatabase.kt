package com.intellij.platform.ae.database.dbs.counter

import com.intellij.platform.ae.database.activities.DatabaseBackedCounterUserActivity
import com.intellij.platform.ae.database.dbs.IInternalUserActivityDatabaseLayer
import java.time.Instant

interface ICounterUserActivityDatabase {
  suspend fun submit(activity: DatabaseBackedCounterUserActivity, diff: Int)
}

internal interface IInternalCounterUserActivityDatabase : IInternalUserActivityDatabaseLayer {
  suspend fun submitDirect(activity: DatabaseBackedCounterUserActivity, diff: Int, instant: Instant)
}

interface IReadOnlyCounterUserActivityDatabase {
  suspend fun getActivitySum(activity: DatabaseBackedCounterUserActivity, from: Instant?, until: Instant?): Int
}