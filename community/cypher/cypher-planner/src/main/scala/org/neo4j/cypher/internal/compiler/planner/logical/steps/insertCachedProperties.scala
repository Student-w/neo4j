package org.neo4j.cypher.internal.compiler.planner.logical.steps

import org.neo4j.cypher.internal.compiler.phases.{LogicalPlanState, PlannerContext}
import org.neo4j.cypher.internal.logical.plans.{CACHED_NODE, CACHED_RELATIONSHIP, CachedProperty, CachedType, CanGetValue, DoNotGetValue, GetValue, IndexLeafPlan, LogicalPlan, ProjectingPlan}
import org.neo4j.cypher.internal.v4_0.expressions.{Property, PropertyKeyName, Variable}
import org.neo4j.cypher.internal.v4_0.frontend.phases.Transformer
import org.neo4j.cypher.internal.v4_0.util.symbols.{CTNode, CTRelationship}
import org.neo4j.cypher.internal.v4_0.util.{InputPosition, Rewriter, bottomUp}

/**
  * A logical plan rewriter that also changes the semantic table (thus a Transformer).
  *
  * It traverses the plan and swaps property lookups for cached properties where possible.
  */
case object insertCachedProperties extends Transformer[PlannerContext, LogicalPlanState, LogicalPlanState] {


  override def transform(from: LogicalPlanState, context: PlannerContext): LogicalPlanState = {

    def isNode(variable: Variable) = from.semanticTable().types.get(variable).exists(t => t.actual == CTNode.invariant)
    def isRel(variable: Variable) = from.semanticTable().types.get(variable).exists(t => t.actual == CTRelationship.invariant)

    case class PropertyUsages(canGetFromIndex: Boolean, usages: Int, cachedType: CachedType) {
      def registerIndexUsage: PropertyUsages = copy(canGetFromIndex = true)
      def addUsage: PropertyUsages = copy(usages = usages + 1)
    }

    val NODE_NO_PROP_USAGE = PropertyUsages(canGetFromIndex = false, 0, CACHED_NODE)
    val REL_NO_PROP_USAGE = PropertyUsages(canGetFromIndex = false, 0, CACHED_RELATIONSHIP)

    case class Acc(properties: Map[Property, PropertyUsages] = Map.empty,
                   renamings: Map[String, String] = Map.empty) {
      def addIndexNodeProperty(prop: Property): Acc = {
        val previousUsages = properties.getOrElse(prop, NODE_NO_PROP_USAGE)
        val newProperties = properties.updated(prop, previousUsages.registerIndexUsage)
        copy(properties = newProperties)
      }
      def addNodeProperty(prop: Property): Acc = {
        val previousUsages = properties.getOrElse(prop, NODE_NO_PROP_USAGE)
        val newProperties = properties.updated(prop, previousUsages.addUsage)
        copy(properties = newProperties)
      }
      def addRelProperty(prop: Property): Acc = {
        val previousUsages = properties.getOrElse(prop, REL_NO_PROP_USAGE)
        val newProperties = properties.updated(prop, previousUsages.addUsage)
        copy(properties = newProperties)
      }
      def addRenamings(additionalRenamings: Map[String, String]): Acc = {
        val newRenamings = renamings ++ additionalRenamings
        copy(renamings = newRenamings)
      }
      def variableWithOriginalName(variable: Variable): Variable = {
        var oldestName = variable.name
        while (renamings.contains(oldestName)) {
          oldestName = renamings(oldestName)
        }
        variable.copy(oldestName)(variable.position)
      }
    }

    // In the first step we collect all property usages and renaming while going over the tree
    val acc = from.logicalPlan.treeFold(Acc()) {
      // Make sure to register any renaming of variables
      case plan: ProjectingPlan => acc =>
        val newRenamings = acc.renamings ++ plan.projectExpressions.collect {
          case (key, v: Variable) if key != v.name => (key, v.name)
        }
        (acc.copy(renamings = newRenamings), Some(identity))

      // Find properties
      case prop@Property(v: Variable, _) if isNode(v) => acc =>
        val originalProp = prop.copy(acc.variableWithOriginalName(v))(prop.position)
        (acc.addNodeProperty(originalProp), Some(identity))
      case prop@Property(v: Variable, _) if isRel(v) => acc =>
        val originalProp = prop.copy(acc.variableWithOriginalName(v))(prop.position)
        (acc.addRelProperty(originalProp), Some(identity))

      // Find index plans that can provide cached properties
      case indexPlan: IndexLeafPlan => acc =>
        val newAcc = indexPlan.properties.filter(_.getValueFromIndex == CanGetValue).foldLeft(acc) { (acc, indexedProp) =>
          val prop = Property(Variable(indexPlan.idName)(InputPosition.NONE), PropertyKeyName(indexedProp.propertyKeyToken.name)(InputPosition.NONE))(InputPosition.NONE)
          acc.addIndexNodeProperty(prop)
        }
        (newAcc, Some(identity))
    }

    var currentTypes = from.semanticTable().types

    // In the second step we rewrite both properties and index plans
    val propertyRewriter = bottomUp(Rewriter.lift {
      // Rewrite properties to be cached if they are used more than once, or can be fetched from an index
      case prop@Property(v: Variable, propertyKeyName) =>
        val originalVar = acc.variableWithOriginalName(v)
        val originalProp = prop.copy(originalVar)(prop.position)
        acc.properties.get(originalProp) match {
          case Some(PropertyUsages(canGetFromIndex, usages, cachedType)) if usages > 1 || canGetFromIndex =>
            // Use the original variable name for the cached property
            val newProperty = CachedProperty(originalVar.name, propertyKeyName, cachedType)(prop.position)
            // Register the new variables in the semantic table
            currentTypes.get(prop) match {
              case None => // I don't like this. We have to make sure we retain the type from semantic analysis
              case Some(currentType) =>
                currentTypes = currentTypes.updated(newProperty, currentType)
            }

            newProperty
          case _ =>
            prop
        }

        // Rewrite index plans to either GetValue or DoNotGetValue
      case indexPlan: IndexLeafPlan =>
        indexPlan.withProperties(indexPlan.properties.map { indexedProp =>
          val prop = Property(Variable(indexPlan.idName)(InputPosition.NONE), PropertyKeyName(indexedProp.propertyKeyToken.name)(InputPosition.NONE))(InputPosition.NONE)
          acc.properties.get(prop) match {
            // Get the value since we use it later
            case Some(PropertyUsages(true, usages, _)) if usages >= 1 =>
              indexedProp.copy(getValueFromIndex = GetValue)
            // We could get the value but we don't need it later
            case _ =>
              indexedProp.copy(getValueFromIndex = DoNotGetValue)
          }
        })

    })

    val plan = propertyRewriter(from.logicalPlan).asInstanceOf[LogicalPlan]
    val newSemanticTable = if (currentTypes == from.semanticTable().types) from.semanticTable() else from.semanticTable().copy(types = currentTypes)
    from.withMaybeLogicalPlan(Some(plan)).withSemanticTable(newSemanticTable)
  }

  override def name: String = "insertCachedProperties"
}