package models

import anorm._
import anorm.SqlParser._
import helpers.DbSetup.insertDocumentSet
import helpers.DbTestContext
import org.specs2.mutable.Specification
import play.api.Play.{ start, stop }
import play.api.test.FakeApplication

class TagSaverSpec extends Specification {
	
  step(start(FakeApplication()))
  
  "TagSaver" should {
    
    "add a new tag to the database, returning id" in new DbTestContext {
      val documentSetId = insertDocumentSet("TagSaverSpec")
      val tagSaver = new TagSaver()
      val name = "a tag"
        
      val id = tagSaver.save(name, documentSetId)

      id must not beNone
      
      val tagId  = SQL("SELECT id FROM tag WHERE name = {name}").
       				on("name" -> name).as(scalar[Long] *).headOption
      
      id must be equalTo(tagId)												            
    }
    
    "return None if attempting to add already existing tag" in new DbTestContext {
      val documentSetId = insertDocumentSet("TagSaverSpec")
      val tagSaver = new TagSaver()
      val name = "a tag"

      val goodId = tagSaver.save(name, documentSetId)
      val noId = tagSaver.save(name, documentSetId)
      
      noId must beNone
    }
  }
  
  step(stop)
}