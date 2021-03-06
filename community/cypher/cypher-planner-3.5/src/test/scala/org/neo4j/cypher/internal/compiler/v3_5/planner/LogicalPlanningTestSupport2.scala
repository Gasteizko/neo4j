/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_5.planner

import org.neo4j.csv.reader.Configuration
import org.neo4j.cypher.internal.compiler.v3_5._
import org.neo4j.cypher.internal.compiler.v3_5.phases._
import org.neo4j.cypher.internal.compiler.v3_5.planner.logical.Metrics._
import org.neo4j.cypher.internal.compiler.v3_5.planner.logical.cardinality.QueryGraphCardinalityModel
import org.neo4j.cypher.internal.compiler.v3_5.planner.logical.idp._
import org.neo4j.cypher.internal.compiler.v3_5.planner.logical.plans.rewriter.unnestApply
import org.neo4j.cypher.internal.compiler.v3_5.planner.logical.steps.replacePropertyLookupsWithVariables
import org.neo4j.cypher.internal.compiler.v3_5.planner.logical.steps.LogicalPlanProducer
import org.neo4j.cypher.internal.compiler.v3_5.planner.logical.steps.devNullListener
import org.neo4j.cypher.internal.compiler.v3_5.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.v3_5.planner.logical._
import org.neo4j.cypher.internal.compiler.v3_5.test_helpers.ContextHelper
import org.neo4j.cypher.internal.ir.v3_5._
import org.neo4j.cypher.internal.planner.v3_5.spi.IndexDescriptor.OrderCapability
import org.neo4j.cypher.internal.planner.v3_5.spi.IndexDescriptor.ValueCapability
import org.neo4j.cypher.internal.planner.v3_5.spi.PlanningAttributes.Cardinalities
import org.neo4j.cypher.internal.planner.v3_5.spi.PlanningAttributes.ProvidedOrders
import org.neo4j.cypher.internal.planner.v3_5.spi.PlanningAttributes.Solveds
import org.neo4j.cypher.internal.planner.v3_5.spi._
import org.neo4j.cypher.internal.v3_5.logical.plans._
import org.neo4j.helpers.collection.Visitable
import org.neo4j.kernel.impl.util.dbstructure.DbStructureVisitor
import org.opencypher.v9_0.ast._
import org.opencypher.v9_0.ast.semantics.SemanticTable
import org.opencypher.v9_0.expressions.PatternExpression
import org.opencypher.v9_0.frontend.phases._
import org.opencypher.v9_0.parser.CypherParser
import org.opencypher.v9_0.rewriting.{Deprecations, RewriterStepSequencer}
import org.opencypher.v9_0.rewriting.RewriterStepSequencer.newPlain
import org.opencypher.v9_0.rewriting.rewriters._
import org.opencypher.v9_0.util.attribution.Attribute
import org.opencypher.v9_0.util.attribution.Attributes
import org.opencypher.v9_0.util.helpers.fixedPoint
import org.opencypher.v9_0.util.test_helpers.CypherFunSuite
import org.opencypher.v9_0.util.test_helpers.CypherTestSupport
import org.opencypher.v9_0.util.Cardinality
import org.opencypher.v9_0.util.PropertyKeyId
import org.scalatest.matchers.BeMatcher
import org.scalatest.matchers.MatchResult

import scala.language.reflectiveCalls
import scala.reflect.ClassTag

trait LogicalPlanningTestSupport2 extends CypherTestSupport with AstConstructionTestSupport with LogicalPlanConstructionTestSupport {
  self: CypherFunSuite =>

  var parser = new CypherParser
  val rewriterSequencer = RewriterStepSequencer.newValidating _
  var astRewriter = new ASTRewriter(rewriterSequencer, literalExtraction = Never, getDegreeRewriting = true)
  final var planner = new QueryPlanner()
  var queryGraphSolver: QueryGraphSolver = new IDPQueryGraphSolver(SingleComponentPlanner(mock[IDPQueryGraphSolverMonitor]), cartesianProductsOrValueJoins, mock[IDPQueryGraphSolverMonitor])
  val cypherCompilerConfig = CypherPlannerConfiguration(
    queryCacheSize = 100,
    statsDivergenceCalculator = StatsDivergenceCalculator.divergenceNoDecayCalculator(0.5, 1000),
    useErrorsOverWarnings = false,
    idpMaxTableSize = DefaultIDPSolverConfig.maxTableSize,
    idpIterationDuration = DefaultIDPSolverConfig.iterationDurationLimit,
    errorIfShortestPathFallbackUsedAtRuntime = false,
    errorIfShortestPathHasCommonNodesAtRuntime = true,
    legacyCsvQuoteEscaping = false,
    csvBufferSize = Configuration.DEFAULT_BUFFER_SIZE_4MB,
    nonIndexedLabelWarningThreshold = 10000,
    planWithMinimumCardinalityEstimates = true,
    lenientCreateRelationship = false
  )
  val realConfig = new RealLogicalPlanningConfiguration(cypherCompilerConfig)

  implicit class LogicalPlanningEnvironment[C <: LogicalPlanningConfiguration](config: C) {
    lazy val semanticTable = config.updateSemanticTableWithTokens(SemanticTable())

    def metricsFactory = new MetricsFactory {
      def newCostModel(ignore: CypherPlannerConfiguration) =
        (plan: LogicalPlan, input: QueryGraphSolverInput, cardinalities: Cardinalities) => config.costModel()((plan, input, cardinalities))

      def newCardinalityEstimator(queryGraphCardinalityModel: QueryGraphCardinalityModel, evaluator: ExpressionEvaluator) =
        config.cardinalityModel(queryGraphCardinalityModel, mock[ExpressionEvaluator])

      def newQueryGraphCardinalityModel(statistics: GraphStatistics) = QueryGraphCardinalityModel.default(statistics)
    }

    def table = Map.empty[PatternExpression, QueryGraph]

    def planContext = new NotImplementedPlanContext {
      override def statistics: GraphStatistics =
        config.graphStatistics

      override def indexesGetForLabel(labelId: Int): Iterator[IndexDescriptor] = {
        val label = config.labelsById(labelId)
        config.indexes.filter(p => p._1 == label).map(p => newIndexDescriptor(p._1, p._2)).iterator
      }

      override def uniqueIndexesGetForLabel(labelId: Int): Iterator[IndexDescriptor] = {
        val label = config.labelsById(labelId)
        config.uniqueIndexes.filter(p => p._1 == label).map(p => newIndexDescriptor(p._1, p._2)).iterator
      }

      private def newIndexDescriptor(labelName: String, propertyKeys: Seq[String]) = {
        // Our fake index either can always or never return property values
        val canGetValue = if (config.indexesWithValues((labelName, propertyKeys))) CanGetValue else DoNotGetValue
        val valueCapability: ValueCapability = _ => propertyKeys.map(_ => canGetValue)
        val orderCapability: OrderCapability = _ => config.indexesWithOrdering.getOrElse((labelName, propertyKeys), IndexOrderCapability.NONE)
        IndexDescriptor(
          semanticTable.resolvedLabelNames(labelName),
          propertyKeys.map(semanticTable.resolvedPropertyKeyNames(_)),
          valueCapability = valueCapability,
          orderCapability = orderCapability
        )
      }

      override def procedureSignature(name: QualifiedName): ProcedureSignature = {
        config.procedureSignatures.find(_.name == name).get
      }

      override def indexExistsForLabel(labelId: Int): Boolean = {
        val labelName = config.labelsById(labelId)
        config.indexes.exists(_._1 == labelName) || config.uniqueIndexes.exists(_._1 == labelName)
      }

      override def indexExistsForLabelAndProperties(labelName: String, propertyKey: Seq[String]): Boolean =
        config.indexes((labelName, propertyKey)) || config.uniqueIndexes((labelName, propertyKey))


      override def indexGetForLabelAndProperties(labelName: String, propertyKeys: Seq[String]): Option[IndexDescriptor] =
        if (config.indexes((labelName, propertyKeys)) || config.uniqueIndexes((labelName, propertyKeys))) {
          Some(newIndexDescriptor(labelName, propertyKeys))
        } else
          None

      override def getOptPropertyKeyId(propertyKeyName: String): Option[Int] =
        semanticTable.resolvedPropertyKeyNames.get(propertyKeyName).map(_.id)

      override def getOptLabelId(labelName: String): Option[Int] =
        semanticTable.resolvedLabelNames.get(labelName).map(_.id)

      override def getOptRelTypeId(relType: String): Option[Int] =
        semanticTable.resolvedRelTypeNames.get(relType).map(_.id)
    }

    def pipeLine(): Transformer[PlannerContext, BaseState, LogicalPlanState] =
      Parsing andThen
      PreparatoryRewriting(Deprecations.V1) andThen
      SemanticAnalysis(warn = true) andThen
      AstRewriting(newPlain, literalExtraction = Never) andThen
      RewriteProcedureCalls andThen
      Namespacer andThen
      transitiveClosure andThen
      rewriteEqualityToInPredicate andThen
      CNFNormalizer andThen
      LateAstRewriting andThen
      ResolveTokens andThen
      CreatePlannerQuery andThen
      OptionalMatchRemover andThen
      QueryPlanner().adds(CompilationContains[LogicalPlan]) andThen
      replacePropertyLookupsWithVariables andThen
      Do[PlannerContext, LogicalPlanState, LogicalPlanState]((state, context) => removeApply(state, context, state.planningAttributes.solveds, Attributes(idGen, state.planningAttributes.cardinalities)))


    private def removeApply(input: LogicalPlanState, context: PlannerContext, solveds: Solveds, attributes: Attributes): LogicalPlanState = {
      val newPlan = input.logicalPlan.endoRewrite(fixedPoint(unnestApply(solveds, attributes)))
      input.copy(maybeLogicalPlan = Some(newPlan))
    }

    def getLogicalPlanFor(queryString: String): (Option[PeriodicCommit], LogicalPlan, SemanticTable, Solveds, Cardinalities) = {
      val mkException = new SyntaxExceptionCreator(queryString, Some(pos))
      val metrics = metricsFactory.newMetrics(planContext.statistics, mock[ExpressionEvaluator], cypherCompilerConfig)
      def context = ContextHelper.create(planContext = planContext,
        exceptionCreator = mkException,
        queryGraphSolver = queryGraphSolver,
        metrics = metrics,
        config = cypherCompilerConfig,
        logicalPlanIdGen = idGen
      )

      val state = InitialState(queryString, None, IDPPlannerName)
      val output = pipeLine().transform(state, context)
      val logicalPlan = output.logicalPlan.asInstanceOf[ProduceResult].source
      (output.periodicCommit, logicalPlan, output.semanticTable(), output.planningAttributes.solveds, output.planningAttributes.cardinalities)
    }

    def estimate(qg: QueryGraph, input: QueryGraphSolverInput = QueryGraphSolverInput.empty) =
      metricsFactory.
        newMetrics(config.graphStatistics, mock[ExpressionEvaluator], cypherCompilerConfig).
        queryGraphCardinalityModel(qg, input, semanticTable)

    def withLogicalPlanningContext[T](f: (C, LogicalPlanningContext) => T): T = {
      val metrics = metricsFactory.newMetrics(config.graphStatistics, mock[ExpressionEvaluator], cypherCompilerConfig)
      val solveds = new Solveds
      val cardinalities = new Cardinalities
      val providedOrders = new ProvidedOrders
      val planningAttributes = new PlanningAttributes(solveds, cardinalities, providedOrders)
      val logicalPlanProducer = LogicalPlanProducer(metrics.cardinality, planningAttributes, idGen)
      val ctx = LogicalPlanningContext(
        planContext = planContext,
        logicalPlanProducer = logicalPlanProducer,
        metrics = metrics,
        semanticTable = semanticTable,
        strategy = queryGraphSolver,
        input = QueryGraphSolverInput.empty,
        notificationLogger = devNullLogger,
        costComparisonListener = devNullListener,
        planningAttributes = planningAttributes
      )
      f(config, ctx)
    }


    def withLogicalPlanningContextWithFakeAttributes[T](f: (C, LogicalPlanningContext) => T): T = {
      val metrics = metricsFactory.newMetrics(config.graphStatistics, mock[ExpressionEvaluator], cypherCompilerConfig)
      val solveds = new StubSolveds
      val cardinalities = new StubCardinalities
      val providedOrders = new StubProvidedOrders
      val planningAttributes = new PlanningAttributes(solveds, cardinalities, providedOrders)
      val logicalPlanProducer = LogicalPlanProducer(metrics.cardinality, planningAttributes, idGen)
      val ctx = LogicalPlanningContext(
        planContext = planContext,
        logicalPlanProducer = logicalPlanProducer,
        metrics = metrics,
        semanticTable = semanticTable,
        strategy = queryGraphSolver,
        input = QueryGraphSolverInput.empty,
        notificationLogger = devNullLogger,
        costComparisonListener = devNullListener,
        planningAttributes = planningAttributes
      )
      f(config, ctx)
    }
  }

  def set[T](plan: LogicalPlan, attribute: Attribute[T], t: T): LogicalPlan = {
    attribute.set(plan.id, t)
    plan
  }

  def setC(plan: LogicalPlan, cardinalities: Cardinalities, c: Cardinality): LogicalPlan = {
    cardinalities.set(plan.id, c)
    plan
  }

  def fakeLogicalPlanFor(id: String*): FakePlan = FakePlan(id.toSet)

  def fakeLogicalPlanFor(planningAttributes: PlanningAttributes, id: String*): FakePlan = {
    val res = FakePlan(id.toSet)
    planningAttributes.solveds.set(res.id, PlannerQuery.empty)
    planningAttributes.cardinalities.set(res.id, 0.0)
    planningAttributes.providedOrders.set(res.id, ProvidedOrder.empty)
    res
  }

  def planFor(queryString: String): (Option[PeriodicCommit], LogicalPlan, SemanticTable, Solveds, Cardinalities) =
    new given().getLogicalPlanFor(queryString)

  class given extends StubbedLogicalPlanningConfiguration(realConfig)

  class givenPlanWithMinimumCardinalityEnabled
    extends StubbedLogicalPlanningConfiguration(RealLogicalPlanningConfiguration(cypherCompilerConfig.copy(planWithMinimumCardinalityEstimates = true)))

  class fromDbStructure(dbStructure: Visitable[DbStructureVisitor])
    extends DelegatingLogicalPlanningConfiguration(DbStructureLogicalPlanningConfiguration(cypherCompilerConfig)(dbStructure))

  implicit def propertyKeyId(label: String)(implicit semanticTable: SemanticTable): PropertyKeyId =
    semanticTable.resolvedPropertyKeyNames(label)

  def using[T <: LogicalPlan](implicit tag: ClassTag[T]): BeMatcher[LogicalPlan] = new BeMatcher[LogicalPlan] {
    import org.opencypher.v9_0.util.Foldable._
    override def apply(actual: LogicalPlan): MatchResult = {
      val matches = actual.treeFold(false) {
        case lp if tag.runtimeClass.isInstance(lp) => acc => (true, None)
      }
      MatchResult(
        matches = matches,
        rawFailureMessage = s"Plan should use ${tag.runtimeClass.getSimpleName}",
        rawNegatedFailureMessage = s"Plan should not use ${tag.runtimeClass.getSimpleName}")
    }
  }
}
