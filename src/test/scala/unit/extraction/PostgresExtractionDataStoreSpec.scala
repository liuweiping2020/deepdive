package org.deepdive.test.unit

import anorm._
import org.deepdive.datastore._
import org.deepdive.extraction._
import org.deepdive.extraction.datastore._
import org.deepdive.test._
import org.scalatest._
import scala.io.Source
import spray.json._
import DefaultJsonProtocol._

class PostgresExtractionDataStoreSpec extends FunSpec with BeforeAndAfter
  with PostgresExtractionDataStoreComponent {

  lazy implicit val connection = PostgresDataStore.borrowConnection()

  before {
    JdbcDataStore.init()
    dataStore.init()
    SQL("drop schema if exists public cascade; create schema public;").execute()
    SQL("""create table datatype_test(id bigserial primary key, key integer, some_text text, 
      some_boolean boolean, some_double double precision, some_null boolean, 
      some_array text[], some_json json);""").execute()
  }

  after {
    JdbcDataStore.close()
  }

  describe("Querying") {

    def insertSampleData() = {
      SQL("""insert into datatype_test(key) 
        VALUES (1), (2), (3), (4)""").execute()
    }

    it("should work for simple attributes") {
      insertSampleData()
      val result = dataStore.queryAsMap("SELECT key from datatype_test;")(_.toList)
      assert(result.head.contains("datatype_test.key"))
    }

    it("should work for alaised attributes") {
      insertSampleData()
      val result = dataStore.queryAsMap("SELECT key AS \"d1.key2\" from datatype_test;")(_.toList)
      assert(result.head.contains("datatype_test.d1.key2"))
    }

    it("should work for aggregated attributes") {
      insertSampleData()
      val result = dataStore.queryAsMap("""SELECT COUNT(*) AS num 
        from datatype_test GROUP BY key""")(_.toList)
      assert(result.head.contains(".num"))
    }

  }

  describe("Serializing to JSON") {  

    def insertSampleRow() : Unit = {
      SQL("""insert into datatype_test(key, some_text, some_boolean, some_double, some_array, some_json) 
        VALUES 
          (1, 'Hello', true, 1.0, '{"A","B"}', '{"hello":"world"}'), 
          (1, 'Ce', false, 2.3, '{"C","D"}', null)""").execute()
    }

    it("should work with aggregate data types") {
      insertSampleRow()
      val result = dataStore.queryAsJson(
        """SELECT key, array_agg(some_text) AS "datatype_test.texts"
        FROM datatype_test GROUP BY key"""
      )(_.toList)
      assert(result.head.asInstanceOf[JsObject].fields == Map[String, JsValue](
        "datatype_test.key" -> JsNumber(1),
        ".datatype_test.texts" -> JsArray(JsString("Hello"), JsString("Ce"))
      ))
    }

    it("should work with simple data types") {
      insertSampleRow()
      val result = dataStore.queryAsJson("SELECT * from datatype_test")(_.toList)
      assert(result.head.asInstanceOf[JsObject].fields == Map[String, JsValue](
        "datatype_test.id" -> JsNumber(1),
        "datatype_test.key" -> JsNumber(1),
        "datatype_test.some_text" -> JsString("Hello"),
        "datatype_test.some_boolean" -> JsBoolean(true),
        "datatype_test.some_double" -> JsNumber(1.0),
        "datatype_test.some_null" -> JsNull,
        "datatype_test.some_array" -> JsArray(List(JsString("A"), JsString("B"))),
        "datatype_test.some_json" -> JsObject("hello" -> JsString("world"))
      ))
    }
  }

  describe ("Building the COPY SQL Statement") {

    it ("should work") {
      val result = dataStore.buildCopySql("someRelation", Set("key1", "key2", "id", "anotherKey"))
      assert(result == "COPY someRelation(id, anotherKey, key1, key2) FROM STDIN CSV")
    }

  }

  describe ("Building the COPY FROM STDIN data") {

    it ("should work") {
      val data = List[JsObject](
       JsObject(Map("key1" -> JsString("hi"), "key2" -> JsString("hello"))),
       JsObject(Map("key1" -> JsString("hi2"), "key2" -> JsNull))
      )
      val resultFile = dataStore.buildCopyData(data, Set("key1", "key2"))
      val result = Source.fromFile(resultFile).getLines.mkString("\n")
      assert(result == "\"0\",\"hi\",\"hello\"\n\"1\",\"hi2\",")
    }
  }

  describe ("Writing to the data store") {

    it("should work") {
      val testRow = JsObject(Map[String, JsValue](
        "key" -> JsNumber(100),
        "some_text" -> JsString("I am sample text."),
        "some_boolean" -> JsBoolean(false),
        "some_double" -> JsNumber(13.37),
        "some_null" -> JsNull,
        "some_array" -> JsArray(List(JsString("13"), JsString("37"))),
        "some_json" -> JsObject("Hello" -> JsString("World"))
      ))
      dataStore.write(List(testRow), "datatype_test")
      val result = dataStore.queryAsJson("SELECT * from datatype_test")(_.toList)
      val resultFields = result.head.fields
      val expectedResult = testRow.fields + Tuple2("id", JsNumber(0))
      assert(resultFields.values.toSet == expectedResult.values.toSet) 
    }

  }
}
  


    