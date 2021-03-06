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
package org.neo4j.cypher.internal.runtime.interpreted.pipes

import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito._
import org.neo4j.cypher.internal.runtime.QueryContext
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.{ListLiteral, Literal, Variable}
import org.neo4j.cypher.internal.runtime.interpreted.{ExecutionContext, ImplicitDummyPos, QueryStateHelper}
import org.neo4j.cypher.internal.v3_5.logical.plans._
import org.neo4j.internal.kernel.api.IndexReference
import org.neo4j.values.storable.Values
import org.neo4j.values.storable.Values.stringValue
import org.neo4j.values.virtual.VirtualValues
import org.opencypher.v9_0.expressions.{LabelName, LabelToken, PropertyKeyName, PropertyKeyToken}
import org.opencypher.v9_0.util.test_helpers.{CypherFunSuite, WindowsStringSafe}
import org.opencypher.v9_0.util.{CypherTypeException, LabelId, PropertyKeyId}

class NodeIndexSeekPipeTest extends CypherFunSuite with ImplicitDummyPos with IndexMockingHelp {

  implicit val windowsSafe = WindowsStringSafe

  private val label = LabelToken(LabelName("LabelName") _, LabelId(11))
  private val propertyKey = Seq(PropertyKeyToken(PropertyKeyName("PropertyName") _, PropertyKeyId(10)))
  override val propertyKeys = propertyKey :+ PropertyKeyToken(PropertyKeyName("prop2") _, PropertyKeyId(11))
  private val node = VirtualValues.node(1)
  private val node2 = VirtualValues.node(2)

  test("should return nodes found by index lookup when both labelId and property key id are solved at compile time") {
    // given
    val queryState = QueryStateHelper.emptyWith(
      query = indexFor[ExecutionContext](Seq("hello") -> Seq(nodeValueHit(node)))
    )

    // when
    val properties = propertyKey.map(IndexedProperty(_, DoNotGetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, SingleQueryExpression(Literal("hello")))()
    val result = pipe.createResults(queryState)

    // then
    result.map(_("n")).toList should equal(List(node))
  }

  test("should handle index lookups for multiple values") {
    // given
    val queryState = QueryStateHelper.emptyWith(
      query = indexFor[ExecutionContext](
        Seq("hello") -> Seq(nodeValueHit(node)),
        Seq("world") -> Seq(nodeValueHit(node2))
      )
    )

    // when
    val properties = propertyKey.map(IndexedProperty(_, DoNotGetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, ManyQueryExpression(ListLiteral(Literal("hello"), Literal("world"))))()
    val result = pipe.createResults(queryState)

    // then
    result.map(_("n")).toList should equal(List(node, node2))
  }

  test("should handle unique index lookups for multiple values") {
    // given
    val queryState = QueryStateHelper.emptyWith(
      query = indexFor[ExecutionContext](
        Seq("hello") -> Seq(nodeValueHit(node)),
        Seq("world") -> Seq(nodeValueHit(node2))
      )
    )

    // when
    val properties = propertyKey.map(IndexedProperty(_, DoNotGetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, ManyQueryExpression(ListLiteral(Literal("hello"), Literal("world"))), UniqueIndexSeek)()
    val result = pipe.createResults(queryState)

    // then
    result.map(_("n")).toList should equal(List(node, node2))
  }

  test("should handle locking unique index lookups for multiple values") {
    // given
    val queryState = QueryStateHelper.emptyWith(
      query = indexFor[ExecutionContext](
        Seq("hello") -> Seq(nodeValueHit(node)),
        Seq("world") -> Seq(nodeValueHit(node2))
      )
    )

    // when
    val properties = propertyKey.map(IndexedProperty(_, DoNotGetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, ManyQueryExpression(ListLiteral(Literal("hello"), Literal("world"))), LockingUniqueIndexSeek)()
    val result = pipe.createResults(queryState)

    // then
    result.map(_("n")).toList should equal(List(node, node2))
  }

  test("should handle index lookups for multiple values when some are null") {
    // given
    val queryState = QueryStateHelper.emptyWith(
      query = indexFor[ExecutionContext](
        Seq("hello") -> Seq(nodeValueHit(node))
      )
    )

    // when
    val properties = propertyKey.map(IndexedProperty(_, DoNotGetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, ManyQueryExpression(
      ListLiteral(
        Literal("hello"),
        Literal(null))))()
    val result = pipe.createResults(queryState)

    // then
    result.map(_("n")).toList should equal(List(node))
  }

  test("should handle unique index lookups for multiple values when some are null") {
    // given
    val queryState = QueryStateHelper.emptyWith(
      query = indexFor[ExecutionContext](
        Seq("hello") -> Seq(nodeValueHit(node))
      )
    )

    // when
    val properties = propertyKey.map(IndexedProperty(_, DoNotGetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, ManyQueryExpression(
      ListLiteral(
        Literal("hello"),
        Literal(null))), UniqueIndexSeek)()
    val result = pipe.createResults(queryState)

    // then
    result.map(_("n")).toList should equal(List(node))
  }

  test("should handle index lookups for IN an empty collection") {
    // given
    val queryState = QueryStateHelper.emptyWith(
      query = indexFor[ExecutionContext](
        Seq("hello") -> Seq(nodeValueHit(node))
      )
    )

    // when
    val properties = propertyKey.map(IndexedProperty(_, DoNotGetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, ManyQueryExpression(ListLiteral()))()
    val result = pipe.createResults(queryState)

    // then
    result.map(_("n")).toList should equal(List.empty)
  }

  test("should handle index lookups for IN a collection with duplicates") {
    // given
    val queryState = QueryStateHelper.emptyWith(// WHERE n.prop IN ['hello', 'hello']
      query = indexFor[ExecutionContext](
        Seq("hello") -> Seq(nodeValueHit(node))
      )
    )

    // when
    val properties = propertyKey.map(IndexedProperty(_, DoNotGetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, ManyQueryExpression(ListLiteral(
      Literal("hello"),
      Literal("hello")
    )))()
    val result = pipe.createResults(queryState)

    // then
    result.map(_("n")).toList should equal(List(node))
  }

  test("should handle index lookups for IN a collection that returns the same nodes for multiple values") {
    // given
    val queryState = QueryStateHelper.emptyWith(// WHERE n.prop IN ['hello', 'hello']
      query = indexFor[ExecutionContext](
        Seq("hello") -> Seq(nodeValueHit(node)),
        Seq("world") -> Seq(nodeValueHit(node))
      )
    )

    // when
    val properties = propertyKey.map(IndexedProperty(_, DoNotGetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, ManyQueryExpression(ListLiteral(
      Literal("hello"),
      Literal("world")
    )))()
    val result = pipe.createResults(queryState)

    // then
    result.map(_("n")).toList should equal(List(node, node))
  }

  test("should handle index lookups for composite index lookups over multiple values") {
    // given
    val queryState = QueryStateHelper.emptyWith(// WHERE n.prop = 'hello' AND n.prop2 = 'world']
      query = indexFor[ExecutionContext](
        Seq("hello", "world") -> Seq(nodeValueHit(node)),
        Seq("hello") -> Seq(nodeValueHit(node), nodeValueHit(node2))
      )
    )

    // when
    val properties = propertyKeys.map(IndexedProperty(_, DoNotGetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties,
      CompositeQueryExpression(Seq(
        SingleQueryExpression(Literal("hello")),
        SingleQueryExpression(Literal("world"))
      )))()
    val result = pipe.createResults(queryState)

    // then
    result.map(_("n")).toList should equal(List(node))
  }

  test("should give a helpful error message") {
    // given
    val queryContext = mock[QueryContext]
    when(queryContext.indexReference(anyInt(), anyInt())).thenReturn(mock[IndexReference])
    val queryState = QueryStateHelper.emptyWith(query = queryContext)

    // when
    val properties = propertyKey.map(IndexedProperty(_, DoNotGetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, ManyQueryExpression(Literal("wut?")))()

    // then
    intercept[CypherTypeException](pipe.createResults(queryState))
  }

  test("should return the node found by the unique index lookup when both labelId and property key id are solved at compile time") {
    // given
    val queryState = QueryStateHelper.emptyWith(query = indexFor[ExecutionContext](Seq("hello") -> Seq(nodeValueHit(node))))

    // when
    val properties = propertyKey.map(IndexedProperty(_, DoNotGetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, SingleQueryExpression(Literal("hello")), UniqueIndexSeek)()
    val result = pipe.createResults(queryState)

    // then
    result.map(_("n")).toList should equal(List(node))
  }

  test("should use existing values from arguments when available") {
    //  GIVEN "hello" as x MATCH a WHERE a.prop = x
    val queryState: QueryState = QueryStateHelper.emptyWith(
      query = indexFor[ExecutionContext](Seq("hello") -> Seq(nodeValueHit(node))),
      initialContext = Some(ExecutionContext.from("x" -> stringValue("hello")))
    )

    // when
    val properties = propertyKey.map(IndexedProperty(_, DoNotGetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, SingleQueryExpression(Variable("x")))()
    val result = pipe.createResults(queryState)

    // then
    result.map(_("n")).toList should equal(List(node))
  }

  test("should use index provided values when available") {
    // given
    val queryState = QueryStateHelper.emptyWith(
      query = indexFor[ExecutionContext](
        Seq("hello") -> Seq(nodeValueHit(node, "hello")),
        Seq("bye") -> Seq(nodeValueHit(node2, "bye"))
      )
    )

    // when
    val properties = propertyKey.map(IndexedProperty(_, GetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, ManyQueryExpression(ListLiteral(
      Literal("hello"),
      Literal("bye")
    )))()
    val result = pipe.createResults(queryState)

    // then
    result.toList should equal(List(
      Map("n" -> node, "n." + propertyKey(0).name -> Values.stringValue("hello")),
      Map("n" -> node2, "n." + propertyKey(0).name -> Values.stringValue("bye"))
    ))
  }

  test("should use composite index provided values when available") {
    // given
    val queryState = QueryStateHelper.emptyWith(
      query = indexFor[ExecutionContext](
        Seq("hello", "world") -> Seq(nodeValueHit(node, "hello", "world")),
        Seq("bye", "cruel") -> Seq(nodeValueHit(node2, "bye", "cruel"))
      )
    )

    // when
    val properties = propertyKeys.map(IndexedProperty(_, GetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties,
      CompositeQueryExpression(Seq(
        ManyQueryExpression(ListLiteral(
          Literal("hello"), Literal("bye")
        )),
        ManyQueryExpression(ListLiteral(
          Literal("world"), Literal("cruel")
        ))
      )))()
    val result = pipe.createResults(queryState)

    // then
    result.toList should equal(List(
      Map("n" -> node, "n." + propertyKeys(0).name -> Values.stringValue("hello"), "n." + propertyKeys(1).name -> Values.stringValue("world")),
      Map("n" -> node2, "n." + propertyKeys(0).name -> Values.stringValue("bye"), "n." + propertyKeys(1).name -> Values.stringValue("cruel"))
    ))
  }

  test("should use locking unique index provided values when available") {
    // given
    val queryState = QueryStateHelper.emptyWith(
      query = indexFor[ExecutionContext](
        Seq("hello") -> Seq(nodeValueHit(node, "hello")),
        Seq("world") -> Seq(nodeValueHit(node2, "bye"))
      )
    )

    // when
    val properties = propertyKey.map(IndexedProperty(_, GetValue)).toArray
    val pipe = NodeIndexSeekPipe("n", label, properties, ManyQueryExpression(ListLiteral(Literal("hello"), Literal("world"))), LockingUniqueIndexSeek)()
    val result = pipe.createResults(queryState)

    // then
    result.toList should equal(List(
      Map("n" -> node, "n." + propertyKey(0).name -> Values.stringValue("hello")),
      Map("n" -> node2, "n." + propertyKey(0).name -> Values.stringValue("bye"))
    ))
  }
}
