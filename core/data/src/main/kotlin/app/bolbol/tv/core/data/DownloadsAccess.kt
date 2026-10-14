package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.DownloadDao

/**
 * The `download` table of the graph's single Room database (v1.4), for `:core:download`'s repository:
 * `DownloadRepositoryImpl(appContext, data.okHttpClient, data.downloadDao)`. The DAO type reaches `:app` through
 * `:core:download`'s `api(project(":core:database"))`.
 */
val DataGraph.downloadDao: DownloadDao get() = database.downloadDao()
