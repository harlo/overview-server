/*
 * NodeWriterSpec.scala
 * 
 * Overview Project
 * Created by Jonas Karlsson, Aug 2012
 */

package org.overviewproject.persistence

import scala.collection.mutable.Set
import anorm.{ sqlToSimple, toParameterValue }
import anorm.SQL
import anorm.SqlParser.{ flatten, get, long, str }
import org.overviewproject.clustering.{ DocTreeNode, DocumentIdCache }
import org.overviewproject.test.DbSetup.{ insertDocument, insertDocumentSet }
import org.overviewproject.test.DbSpecification
import org.specs2.execute.PendingUntilFixed

class NodeWriterSpec extends DbSpecification {

  step(setupDb)

  private def addChildren(parent: DocTreeNode, description: String): Seq[DocTreeNode] = {
    val children = for (i <- 1 to 2) yield new DocTreeNode(Set())
    children.foreach(addCache)
    children.foreach(_.description = description)
    children.foreach(parent.children.add)

    children
  }

  // add a dummy cache that's not used for anything
  private def addCache(node: DocTreeNode) {
    node.documentIdCache = new DocumentIdCache(10, Array[Long](1l, 2l, 3l, 4l))
  }

  private val nodeDataParser = long("id") ~ str("description") ~
    get[Option[Long]]("parent_id") ~ long("document_set_id")

  "NodeWriter" should {
    
    trait NodeWriterContext extends DbTestContext {
      var documentSetId: Long = _
      var writer: NodeWriter = _
      
      override def setupWithDb = {
        documentSetId = insertDocumentSet("NodeWriterSpec")
        writer = new NodeWriter(documentSetId)
      }
    }

    "insert root node with description, document set, and no parent" in new NodeWriterContext {
      val root = new DocTreeNode(Set())
      val description = "description"
      root.description = description
      addCache(root)

      writer.write(root)

      val result =
        SQL("SELECT id, description, parent_id, document_set_id FROM node").
          as(nodeDataParser map (flatten) singleOpt)

      result must beSome
      val (id, rootDescription, parentId, rootDocumentSetId) = result.get

      rootDescription must be equalTo (description)
      parentId must beNone
      rootDocumentSetId must be equalTo (documentSetId)
    }

    "insert child nodes" in new NodeWriterContext {
      val root = new DocTreeNode(Set())
      root.description = "root"
      addCache(root)
      val childNodes = addChildren(root, "child")
      val grandChildNodes = childNodes.map(n => (n, addChildren(n, "grandchild")))

      writer.write(root)

      val savedRoot = SQL("""
                          SELECT id, description, parent_id, document_set_id FROM node
                          WHERE description = 'root'
                          """).as(nodeDataParser map (flatten) singleOpt)

      savedRoot must beSome
      val (rootId, _, _, _) = savedRoot.get

      val savedChildren =
        SQL("""
    	    SELECT id, description, parent_id, document_set_id FROM node
            WHERE parent_id = {rootId} AND description = 'child'
    		""").on("rootId" -> rootId).as(nodeDataParser map (flatten) *)

      val childIds = savedChildren.map(_._1)
      childIds must have size (2)

      val savedGrandChildren =
        SQL("""
    	    SELECT id, description, parent_id, document_set_id FROM node
            WHERE parent_id IN """ + childIds.mkString("(", ",", ")") + """ 
            AND description = 'grandchild'
    		""").on("rootId" -> rootId).as(nodeDataParser map (flatten) *)

      savedGrandChildren must have size (4)
    }

    "insert document into node_document table" in new NodeWriterContext {
      val documentIds = for (i <- 1 to 5) yield insertDocument(documentSetId, "title", "documentCloudId")
      val idSet = Set(documentIds: _*)

      val node = new DocTreeNode(idSet)
      node.description = "node"
      addCache(node)

      writer.write(node)

      val savedNode = SQL("SELECT id FROM node WHERE description = 'node'").
        as(long("id") singleOpt)

      savedNode must beSome
      val nodeId = savedNode.get

      val nodeDocuments =
        SQL("""
            SELECT node_id, document_id FROM node_document
            """).as(long("node_id") ~ long("document_id") map (flatten) *)

      val expectedNodeDocuments = documentIds.map((nodeId, _))

      nodeDocuments must haveTheSameElementsAs(expectedNodeDocuments)
    }
    
    "write nodes with ids generated from documentSetId" in new NodeWriterContext {
      val node = new DocTreeNode(Set())
      addCache(node)
      writer.write(node)
      val nodeId = SQL("SELECT id FROM node").as(long("id") singleOpt)
      
      nodeId must beSome
      (nodeId.get >> 32) must be equalTo(documentSetId) 
    }
  }

  step(shutdownDb)
}
