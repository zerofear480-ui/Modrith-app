package com.modrith.app.di

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import com.modrith.app.install.AndroidInstallWorkflow
import com.modrith.app.install.PersistentCheckpointRepository
import com.modrith.app.install.SafLauncherTargetRegistry
import com.modrith.core.di.IoDispatcher
import com.modrith.downloader.OkHttpDownloadEngine
import com.modrith.downloader.persistence.DownloadStateDatabase
import com.modrith.downloader.persistence.RoomDownloadRepository
import com.modrith.filesystem.CacheProvider
import com.modrith.installer.DefaultInstallEngine
import com.modrith.installer.InstallEngine
import com.modrith.installer.InstallRepository
import com.modrith.installer.StorageInstallRepository
import com.modrith.launcher.DefaultCSLauncherProvider
import com.modrith.launcher.LauncherProvider
import com.modrith.models.DownloadEngine
import com.modrith.orchestrator.DefaultInstallManager
import com.modrith.orchestrator.DefaultMrPackSourceResolver
import com.modrith.orchestrator.DefaultOrchestratorParser
import com.modrith.orchestrator.DefaultOrchestratorResolver
import com.modrith.orchestrator.InstallCoordinator
import com.modrith.orchestrator.InstallLogger
import com.modrith.orchestrator.InstallManager
import com.modrith.orchestrator.InstallPipeline
import com.modrith.orchestrator.JvmInstallLogger
import com.modrith.orchestrator.LauncherTargetProvider
import com.modrith.orchestrator.MrPackSourceResolver
import com.modrith.orchestrator.OrchestratorCheckpointRepository
import com.modrith.orchestrator.OrchestratorParser
import com.modrith.orchestrator.OrchestratorResolver
import com.modrith.ui.install.InstallWorkflow
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.nio.file.Files
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class InstallBindingsModule {
    @Binds
    @Singleton
    abstract fun bindCheckpointRepository(
        repository: PersistentCheckpointRepository,
    ): OrchestratorCheckpointRepository

    @Binds
    @Singleton
    abstract fun bindLauncherTargetProvider(
        registry: SafLauncherTargetRegistry,
    ): LauncherTargetProvider

    @Binds
    @Singleton
    abstract fun bindInstallWorkflow(
        workflow: AndroidInstallWorkflow,
    ): InstallWorkflow
}

@Module
@InstallIn(SingletonComponent::class)
object InstallProvidersModule {
    @Provides
    fun provideContentResolver(
        @ApplicationContext context: Context,
    ): ContentResolver = context.contentResolver

    @Provides
    @Singleton
    fun provideDownloadStateDatabase(
        @ApplicationContext context: Context,
    ): DownloadStateDatabase = Room.databaseBuilder(
        context,
        DownloadStateDatabase::class.java,
        "download-state.db",
    ).build()

    @Provides
    @Singleton
    fun provideDownloadEngine(
        client: OkHttpClient,
        database: DownloadStateDatabase,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): DownloadEngine = OkHttpDownloadEngine(
        baseClient = client,
        repository = RoomDownloadRepository(database.downloadStateDao()),
        dispatcher = dispatcher,
    )

    @Provides
    @Singleton
    fun provideInstallRepository(
        @ApplicationContext context: Context,
    ): InstallRepository {
        val root = context.filesDir.toPath().resolve("installer-state")
        Files.createDirectories(root)
        return StorageInstallRepository(CacheProvider(root))
    }

    @Provides
    @Singleton
    fun provideInstallEngine(
        repository: InstallRepository,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): InstallEngine = DefaultInstallEngine(
        repository = repository,
        dispatcher = dispatcher,
    )

    @Provides
    @Singleton
    fun provideLauncherProvider(): LauncherProvider = DefaultCSLauncherProvider()

    @Provides
    @Singleton
    fun provideInstallLogger(): InstallLogger = JvmInstallLogger()

    @Provides
    @Singleton
    fun provideOrchestratorParser(
        logger: InstallLogger,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): OrchestratorParser = DefaultOrchestratorParser(logger, dispatcher)

    @Provides
    @Singleton
    fun provideOrchestratorResolver(): OrchestratorResolver =
        DefaultOrchestratorResolver()

    @Provides
    @Singleton
    fun provideMrPackSourceResolver(
        contentResolver: ContentResolver,
    ): MrPackSourceResolver = DefaultMrPackSourceResolver(contentResolver)

    @Provides
    @Singleton
    fun provideInstallPipeline(
        parser: OrchestratorParser,
        resolver: OrchestratorResolver,
        downloadEngine: DownloadEngine,
        installEngine: InstallEngine,
        launcherProvider: LauncherProvider,
        launcherTargetProvider: LauncherTargetProvider,
        sourceResolver: MrPackSourceResolver,
        checkpoints: OrchestratorCheckpointRepository,
        logger: InstallLogger,
    ): InstallPipeline = InstallPipeline(
        parser = parser,
        resolver = resolver,
        downloadEngine = downloadEngine,
        installEngine = installEngine,
        launcherProvider = launcherProvider,
        launcherTargetProvider = launcherTargetProvider,
        sourceResolver = sourceResolver,
        checkpoints = checkpoints,
        logger = logger,
    )

    @Provides
    @Singleton
    fun provideInstallManager(
        pipeline: InstallPipeline,
        checkpoints: OrchestratorCheckpointRepository,
        logger: InstallLogger,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): InstallManager = DefaultInstallManager(
        InstallCoordinator(
            pipeline = pipeline,
            checkpoints = checkpoints,
            logger = logger,
            dispatcher = dispatcher,
        ),
    )
}
