package com.modrith.orchestrator

import com.modrith.models.InstallPlan
import com.modrith.models.MrPackParseResult
import com.modrith.models.ResolvedInstallPlan
import com.modrith.parser.mrpack.MrPackParser
import com.modrith.parser.mrpack.ParserLogger
import com.modrith.resolver.InstallPlanResolver
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DefaultOrchestratorParser(
    private val logger: InstallLogger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OrchestratorParser {
    override suspend fun parse(
        archive: Path,
        cacheDirectory: Path,
        sessionId: String,
    ): MrPackParseResult = withContext(dispatcher) {
        MrPackParser(
            logger = ParserLogAdapter(sessionId, logger),
        ).parse(archive, cacheDirectory)
    }
}

class DefaultOrchestratorResolver(
    private val resolver: InstallPlanResolver = InstallPlanResolver(),
) : OrchestratorResolver {
    override suspend fun resolve(plan: InstallPlan): ResolvedInstallPlan = resolver.resolve(plan)
}

private class ParserLogAdapter(
    private val sessionId: String,
    private val logger: InstallLogger,
) : ParserLogger {
    override fun debug(event: String, attributes: Map<String, Any?>) =
        emit(InstallLogLevel.DEBUG, event, attributes)

    override fun info(event: String, attributes: Map<String, Any?>) =
        emit(InstallLogLevel.INFO, event, attributes)

    override fun warn(
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable?,
    ) = emit(InstallLogLevel.WARNING, event, attributes, cause)

    override fun error(
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable?,
    ) = emit(InstallLogLevel.ERROR, event, attributes, cause)

    private fun emit(
        level: InstallLogLevel,
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable? = null,
    ) {
        logger.log(
            sessionId,
            InstallLogSource.PARSER,
            level,
            event,
            attributes,
            cause,
        )
    }
}
