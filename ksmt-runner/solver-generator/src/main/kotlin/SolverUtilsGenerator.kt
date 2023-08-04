import io.ksmt.KContext
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverUniversalConfigurationBuilder
import io.ksmt.solver.bitwuzla.KBitwuzlaSolver
import io.ksmt.solver.bitwuzla.KBitwuzlaSolverUniversalConfiguration
import io.ksmt.solver.cvc5.KCvc5Solver
import io.ksmt.solver.cvc5.KCvc5SolverUniversalConfiguration
import io.ksmt.solver.yices.KYicesSolver
import io.ksmt.solver.yices.KYicesSolverUniversalConfiguration
import io.ksmt.solver.z3.KZ3Solver
import io.ksmt.solver.z3.KZ3SolverUniversalConfiguration
import kotlin.io.path.Path
import kotlin.io.path.bufferedWriter
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

val solvers = listOf(
    SolverDescription("Z3", KZ3Solver::class, KZ3SolverUniversalConfiguration::class),
    SolverDescription("Bitwuzla", KBitwuzlaSolver::class, KBitwuzlaSolverUniversalConfiguration::class),
    SolverDescription("Yices", KYicesSolver::class, KYicesSolverUniversalConfiguration::class),
    SolverDescription("Cvc5", KCvc5Solver::class, KCvc5SolverUniversalConfiguration::class),
)

data class SolverDescription(
    val solverType: String,
    val solverCls: KClass<out KSolver<*>>,
    val solverUniversalConfig: KClass<out KSolverConfiguration>
)

private const val SOLVER_TYPE = "SolverType"
private const val SOLVER_TYPE_QUALIFIED_NAME = "io.ksmt.runner.generated.models.SolverType"
private const val CUSTOM_SOLVER_TYPE = "${SOLVER_TYPE}.Custom"

private val kSolverTypeName = "${KSolver::class.simpleName}<*>"
private val kContextTypeName = "${KContext::class.simpleName}"
private val kSolverConfigTypeName = "${KSolverConfiguration::class.simpleName}"
private val kSolverConfigBuilderTypeName = "${KSolverUniversalConfigurationBuilder::class.simpleName}"

private const val CONFIG_CONSTRUCTOR = "configConstructor"
private const val SOLVER_CONSTRUCTOR = "solverConstructor"
private const val CONFIG_CONSTRUCTOR_CREATOR = "createConfigConstructor"
private const val SOLVER_CONSTRUCTOR_CREATOR = "createSolverConstructor"

private fun generateHeader(packageName: String) = """
    /**
     * Do not edit.
     * Generated by SolverUtilsGenerator.
     * */
    
    package $packageName

    import ${KContext::class.qualifiedName}
    import $SOLVER_TYPE_QUALIFIED_NAME
    import ${KSolver::class.qualifiedName}
    import ${KSolverConfiguration::class.qualifiedName}
    import ${KSolverUniversalConfigurationBuilder::class.qualifiedName}
    import ${KClass::class.qualifiedName}

    typealias ConfigurationBuilder<C> = ($kSolverConfigBuilderTypeName) -> C
""".trimIndent()

private fun checkHasSuitableSolverConstructor(solver: SolverDescription) {
    val constructor = solver.solverCls.constructors
        .filter { it.parameters.size == 1 }
        .find { it.parameters.single().type == KContext::class.createType() }

    check(constructor != null) { "No constructor for solver $solver" }
}

private fun checkHasSuitableConfigConstructor(solver: SolverDescription) {
    val constructor = solver.solverUniversalConfig.constructors
        .filter { it.parameters.size == 1 }
        .find { it.parameters.single().type == KSolverUniversalConfigurationBuilder::class.createType() }

    check(constructor != null) { "No constructor for solver $solver" }
}

private fun generateSolverConstructor(): String = """
        internal fun $SOLVER_CONSTRUCTOR_CREATOR(solverQualifiedName: String): ($kContextTypeName) -> $kSolverTypeName {
            val cls = Class.forName(solverQualifiedName)
            val ctor = cls.getConstructor($kContextTypeName::class.java)
            return { ctx: $kContextTypeName -> ctor.newInstance(ctx) as $kSolverTypeName }
        }
    """.trimIndent()

private fun generateSolverConstructor(solver: SolverDescription): String {
    checkHasSuitableSolverConstructor(solver)

    return """
        private val $SOLVER_CONSTRUCTOR${solver.solverType}: ($kContextTypeName) -> $kSolverTypeName by lazy {
            $SOLVER_CONSTRUCTOR_CREATOR("${solver.solverCls.qualifiedName}")
        }
    """.trimIndent()
}

private fun generateConfigConstructor(): String = """
        internal fun $CONFIG_CONSTRUCTOR_CREATOR(
            configQualifiedName: String
        ): ($kSolverConfigBuilderTypeName) -> $kSolverConfigTypeName {
            val cls = Class.forName(configQualifiedName)
            val ctor = cls.getConstructor($kSolverConfigBuilderTypeName::class.java)
            return { builder: $kSolverConfigBuilderTypeName -> ctor.newInstance(builder) as $kSolverConfigTypeName }
        }
    """.trimIndent()

@Suppress("MaxLineLength")
private fun generateConfigConstructor(solver: SolverDescription): String {
    checkHasSuitableConfigConstructor(solver)

    return """
        private val $CONFIG_CONSTRUCTOR${solver.solverType}: ($kSolverConfigBuilderTypeName) -> $kSolverConfigTypeName by lazy {
            $CONFIG_CONSTRUCTOR_CREATOR("${solver.solverUniversalConfig.qualifiedName}")
        }
    """.trimIndent()
}

private fun generateSolverTypeMapping(prefix: String) = solvers.joinToString("\n") {
    """$prefix"${it.solverCls.qualifiedName}" to $SOLVER_TYPE.${it.solverType},"""
}

private fun generateSolverTypeGetter(): String = """
    |private val solverTypes = mapOf(
    ${generateSolverTypeMapping(prefix = "|    ")}
    |)
    |
    |val KClass<out ${kSolverTypeName}>.solverType: $SOLVER_TYPE
    |    get() = solverTypes[qualifiedName] ?: $CUSTOM_SOLVER_TYPE
""".trimMargin()

private fun generateSolverInstanceCreation(prefix: String) = solvers.joinToString("\n") {
    """$prefix${SOLVER_TYPE}.${it.solverType} -> $SOLVER_CONSTRUCTOR${it.solverType}(ctx)"""
}

private fun generateSolverCreateInstance(): String = """
    |fun $SOLVER_TYPE.createInstance(ctx: $kContextTypeName): $kSolverTypeName = when (this) {
    ${generateSolverInstanceCreation(prefix = "|    ")}
    |    $CUSTOM_SOLVER_TYPE -> error("User defined solvers should not be created with this builder")
    |}
""".trimMargin()

private fun generateConfigInstanceCreation(prefix: String) = solvers.joinToString("\n") {
    """$prefix${SOLVER_TYPE}.${it.solverType} -> { builder -> $CONFIG_CONSTRUCTOR${it.solverType}(builder) as C }"""
}

private fun generateConfigCreateInstance(): String = """
    |@Suppress("UNCHECKED_CAST")
    |fun <C : $kSolverConfigTypeName> $SOLVER_TYPE.createConfigurationBuilder(): ConfigurationBuilder<C> = when (this) {
    ${generateConfigInstanceCreation(prefix = "|    ")}
    |    $CUSTOM_SOLVER_TYPE -> error("User defined solver config builders should not be created with this builder")
    |}
""".trimMargin()

fun main(args: Array<String>) {
    val (generatedFilePath, generatedFilePackage) = args

    Path(generatedFilePath, "SolverUtils.kt").bufferedWriter().use {
        it.appendLine(generateHeader(generatedFilePackage))
        it.newLine()

        it.appendLine(generateSolverConstructor())
        it.newLine()

        it.appendLine(generateConfigConstructor())
        it.newLine()

        solvers.forEach { solver ->
            it.appendLine(generateSolverConstructor(solver))
            it.newLine()
            it.appendLine(generateConfigConstructor(solver))
            it.newLine()
        }

        it.appendLine(generateSolverTypeGetter())
        it.newLine()

        it.appendLine(generateSolverCreateInstance())
        it.newLine()

        it.appendLine(generateConfigCreateInstance())
        it.newLine()
    }
}