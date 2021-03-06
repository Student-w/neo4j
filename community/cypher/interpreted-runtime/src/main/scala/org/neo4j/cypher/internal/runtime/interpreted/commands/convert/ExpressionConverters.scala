/*
 * Copyright (c) 2002-2020 "Neo4j,"
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
package org.neo4j.cypher.internal.runtime.interpreted.commands.convert

import org.neo4j.cypher.internal.logical.plans.{ManySeekableArgs, SeekableArgs, SingleSeekableArg}
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.ProjectedPath._
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.{ProjectedPath, Expression => CommandExpression}
import org.neo4j.cypher.internal.runtime.interpreted.commands.predicates
import org.neo4j.cypher.internal.runtime.interpreted.commands.predicates.Predicate
import org.neo4j.cypher.internal.runtime.interpreted.pipes.{ManySeekArgs, SeekArgs, SingleSeekArg}
import org.neo4j.cypher.internal.runtime.interpreted.{CommandProjection, GroupingExpression}
import org.neo4j.cypher.internal.v4_0.expressions.{LogicalVariable, SemanticDirection}
import org.neo4j.cypher.internal.v4_0.util._
import org.neo4j.cypher.internal.v4_0.util.attribution.Id
import org.neo4j.cypher.internal.v4_0.{expressions => ast}
import org.neo4j.exceptions.InternalException
import org.neo4j.graphdb.Direction

trait ExpressionConverter {
  def toCommandExpression(id: Id, expression: ast.Expression, self: ExpressionConverters): Option[CommandExpression]
  def toCommandProjection(id: Id, projections: Map[String, ast.Expression], self: ExpressionConverters): Option[CommandProjection]
  def toGroupingExpression(id: Id, groupings: Map[String, ast.Expression], orderToLeverage: Seq[ast.Expression], self: ExpressionConverters): Option[GroupingExpression]
}

class ExpressionConverters(converters: ExpressionConverter*) {

  self =>

  def toCommandExpression(id: Id, expression: ast.Expression): CommandExpression = {
    converters foreach { c: ExpressionConverter =>
        c.toCommandExpression(id, expression, this) match {
          case Some(x) => return x
          case None =>
        }
    }

    throw new InternalException(s"Unknown expression type during transformation (${expression.getClass})")
  }

    def toCommandProjection(id: Id, projections: Map[String, ast.Expression]): CommandProjection = {
      converters foreach { c: ExpressionConverter =>
        c.toCommandProjection(id, projections, this) match {
          case Some(x) => return x
          case None =>
        }
      }

    throw new InternalException(s"Unknown projection type during transformation ($projections)")
  }

  def toGroupingExpression(id: Id, groupings: Map[String, ast.Expression], orderToLeverage: Seq[ast.Expression]): GroupingExpression = {
    converters foreach { c: ExpressionConverter =>
      c.toGroupingExpression(id, groupings, orderToLeverage, this) match {
        case Some(x) => return x
        case None =>
      }
    }

    throw new InternalException(s"Unknown grouping type during transformation ($groupings)")
  }

  def toCommandPredicate(id: Id, in: ast.Expression): Predicate = in match {
    case e: ast.PatternExpression => predicates.NonEmpty(toCommandExpression(id, e))
    case e: ast.ListComprehension => predicates.NonEmpty(toCommandExpression(id, e))
    case e => toCommandExpression(id, e) match {
      case c: Predicate => c
      case c => predicates.CoercedPredicate(c)
    }
  }

  def toCommandPredicate(id: Id, expression: Option[ast.Expression]): Predicate =
    expression.map(e => self.toCommandPredicate(id, e)).getOrElse(predicates.True())

  def toCommandSeekArgs(id: Id, seek: SeekableArgs): SeekArgs = seek match {
    case SingleSeekableArg(expr) => SingleSeekArg(toCommandExpression(id, expr))
    case ManySeekableArgs(expr) => expr match {
      case coll: ast.ListLiteral =>
        ZeroOneOrMany(coll.expressions) match {
          case Zero => SeekArgs.empty
          case One(value) => SingleSeekArg(toCommandExpression(id, value))
          case Many(_) => ManySeekArgs(toCommandExpression(id, coll))
        }

      case _ =>
        ManySeekArgs(toCommandExpression(id, expr))
    }
  }

  def toCommandProjectedPath(e: ast.PathExpression): ProjectedPath = {
    def project(pathStep: ast.PathStep): Projector = pathStep match {

      case ast.NodePathStep(node: LogicalVariable, next) =>
        singleNodeProjector(node.name, project(next))

      case ast.SingleRelationshipPathStep(rel: LogicalVariable, _, Some(target: LogicalVariable), next) =>
        singleRelationshipWithKnownTargetProjector(rel.name, target.name, project(next))

      case ast.SingleRelationshipPathStep(rel: LogicalVariable, SemanticDirection.INCOMING, _, next) =>
        singleIncomingRelationshipProjector(rel.name, project(next))

      case ast.SingleRelationshipPathStep(rel: LogicalVariable, SemanticDirection.OUTGOING, _, next) =>
        singleOutgoingRelationshipProjector(rel.name, project(next))

      case ast.SingleRelationshipPathStep(rel: LogicalVariable, SemanticDirection.BOTH, _, next) =>
        singleUndirectedRelationshipProjector(rel.name, project(next))

      case ast.MultiRelationshipPathStep(rel: LogicalVariable, SemanticDirection.INCOMING, _, next) =>
        multiIncomingRelationshipProjector(rel.name, project(next))

      case ast.MultiRelationshipPathStep(rel: LogicalVariable, SemanticDirection.OUTGOING, _, next) =>
        multiOutgoingRelationshipProjector(rel.name, project(next))

      case ast.MultiRelationshipPathStep(rel: LogicalVariable, SemanticDirection.BOTH, _, next) =>
        multiUndirectedRelationshipProjector(rel.name, project(next))

      case ast.NilPathStep =>
        nilProjector

      case x =>
        throw new IllegalArgumentException(s"Unknown pattern part found in expression: $x")
    }

    val projector = project(e.step)
    val dependencies = e.step.dependencies.map(_.asInstanceOf[LogicalVariable].name)

    ProjectedPath(dependencies, projector)
  }
}

object DirectionConverter {
  def toGraphDb(dir: SemanticDirection): Direction = dir match {
    case SemanticDirection.INCOMING => Direction.INCOMING
    case SemanticDirection.OUTGOING => Direction.OUTGOING
    case SemanticDirection.BOTH => Direction.BOTH
  }
}
