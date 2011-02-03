package au.org.ala.biocache

import com.google.gson.Gson
import collection.mutable.ArrayBuffer
import org.wyki.cassandra.pelops.{Selector, Pelops}
import collection.JavaConversions
import org.apache.cassandra.thrift.{SlicePredicate, Column, ConsistencyLevel}

/**
 * This trait should be implemented for Cassandra,
 * but could also be implemented for Google App Engine
 * or another backend supporting basic key value pair storage
 *
 * @author Dave Martin (David.Martin@csiro.au)
 */
trait PersistenceManager {

    /**
     * Get a single property.
     */
    def get(uuid: String, entityName:String, propertyName: String): Option[String]

    /**
     * Get a key value pair map for this record.
     */
    def get(uuid: String, entityName:String): Option[Map[String, String]]

    /**
     * Put a single property.
     */
    def put(uuid: String, entityName:String, propertyName: String, propertyValue: String)

    /**
     * Put a set of key value pairs.
     */
    def put(uuid: String, entityName:String, keyValuePairs: Map[String, String])

    /**
     * Retrieve an array of objects.
     */
    def getArray(uuid: String, entityName:String, propertyName: String, theClass: java.lang.Class[AnyRef]): Array[AnyRef]

    /**
     * @overwrite if true, current stored value will be replaced without a read.
     */
    def putArray(uuid: String, entityName:String, propertyName: String, propertyArray: Array[AnyRef], overwrite: Boolean)

    /**
     * Page over all entities, passing the retrieved UUID and property map to the supplied function.
     * Function should return false to exit paging.
     */
    def pageOverAll(entityName:String, proc:((String, Map[String,String])=>Boolean))

    /**
     * Select the properties for the supplied record UUIDs
     */
    def selectRows(uuids:Array[String],entityName:String,propertyNames:Array[String],proc:((Map[String,String])=>Unit))
}

/**
 *   Cassandra based implementation of a persistence manager.
 * This should maintain most of the cassandra logic
 *
 * TODO - Implement remaining methods, and remove cassandra logic from DAOs.
 * Also start using a DI framework.
 */
object CassandraPersistenceManager extends PersistenceManager {

    import JavaConversions._

    val keyspace = "occ"

    /**
     * Retrieve an array of objects, parsing the JSON stored.
     */
    def get(uuid:String, entityName:String) = {
        val selector = Pelops.createSelector(DAO.poolName,keyspace)
        val slicePredicate = Selector.newColumnsPredicateAll(true, DAO.maxColumnLimit)
        try {
            val columnList = selector.getColumnsFromRow(uuid, entityName, slicePredicate, ConsistencyLevel.ONE)
            Some(columnList2Map(columnList))
        } catch {
            case e:Exception => None
        }
    }

    def get(uuid:String, entityName:String, propertyName:String) = {
      try {
          val selector = Pelops.createSelector(DAO.poolName, keyspace)
          val column = selector.getColumnFromRow(uuid, entityName, propertyName.getBytes, ConsistencyLevel.ONE)
          Some(new String(column.value))
      } catch {
          case e:Exception => None
      }
    }

    def put(uuid:String, entityName:String, keyValuePairs:Map[String, String]) = {
        val mutator = Pelops.createMutator(DAO.poolName, keyspace)
        keyValuePairs.foreach( keyValue => {
          mutator.writeColumn(uuid, entityName, mutator.newColumn(keyValue._1.getBytes, keyValue._2))
        })
        mutator.execute(ConsistencyLevel.ONE)
    }

    /**
     * Store the supplied property value in the column
     */
    def put(uuid:String, entityName:String, propertyName:String, propertyValue:String) = {
        val mutator = Pelops.createMutator(DAO.poolName, keyspace)
        mutator.writeColumn(uuid, entityName, mutator.newColumn(propertyName.getBytes, propertyValue))
        mutator.execute(ConsistencyLevel.ONE)
    }

    /**
     * Retrieve the column value, and parse from JSON to Array
     */
    def getArray(uuid:String, entityName:String, propertyName:String, theClass:java.lang.Class[AnyRef]): Array[AnyRef] = {
        val column = getColumn(uuid, entityName, propertyName)
        if (column.isEmpty) {
            Array()
        } else {
            val gson = new Gson
            val currentJson = new String(column.get.getValue)
            gson.fromJson(currentJson, theClass).asInstanceOf[Array[AnyRef]]
        }
    }

    /**
     * Store arrays in a single column as JSON.
     */
    def putArray(uuid:String, entityName:String, propertyName:String, propertyArray:Array[AnyRef], overwrite:Boolean) = {

        //initialise the serialiser
        val gson = new Gson
        val mutator = Pelops.createMutator(DAO.poolName, keyspace)

        if (overwrite) {

            val json = gson.toJson(propertyArray)
            mutator.writeColumn(uuid, entityName, mutator.newColumn(propertyName, json))

        } else {

            //retrieve existing values
            val column = getColumn(uuid, entityName, propertyName)
            //if empty, write, if populated resolve
            if (column.isEmpty) {
                //write new values
                val json = gson.toJson(propertyArray)
                mutator.writeColumn(uuid, entityName, mutator.newColumn(propertyName, json))
            } else {
                //retrieve the existing objects
                val currentJson = new String(column.get.getValue)
                var objectList = gson.fromJson(currentJson, propertyArray.getClass).asInstanceOf[Array[AnyRef]]

                var written = false
                var buffer = new ArrayBuffer[AnyRef]

                for (theObject <- objectList) {
                    if (!propertyArray.contains(theObject)) {
                        //add to buffer
                        buffer + theObject
                    }
                }

                //PRESERVE UNIQUENESS
                buffer ++= propertyArray

                // check equals
                val newJson = gson.toJson(buffer.toArray)
                mutator.writeColumn(uuid, entityName, mutator.newColumn(propertyName, newJson))
            }
        }
        mutator.execute(ConsistencyLevel.ONE)
    }

    /**
     * Iterate over all occurrences, passing the objects to a function.
     * Function returns a boolean indicating if the paging should continue.
     *
     * @param occurrenceType
     * @param proc
     */
    def pageOverAll(entityName:String, proc:((String, Map[String,String])=>Boolean) ) {

      val selector = Pelops.createSelector(DAO.poolName, keyspace)
      val slicePredicate = Selector.newColumnsPredicateAll(true, DAO.maxColumnLimit)
      var startKey = ""
      var keyRange = Selector.newKeyRange(startKey, "", 1001)
      var hasMore = true
      var counter = 0
      var columnMap = selector.getColumnsFromRows(keyRange, entityName, slicePredicate, ConsistencyLevel.ONE)
      var continue = true
      while (columnMap.size>0 && continue) {
        val columnsObj = List(columnMap.keySet.toArray : _*)
        //convert to scala List
        val keys = columnsObj.asInstanceOf[List[String]]
        startKey = keys.last
        for(uuid<-keys){
          val columnList = columnMap.get(uuid)
          //procedure a map of key value pairs
          val map = columnList2Map(columnList)
          //pass the record ID and the key value pair map to the proc
          continue = proc(uuid, map)
        }
        counter += keys.size
        keyRange = Selector.newKeyRange(startKey, "", 1001)
        columnMap = selector.getColumnsFromRows(keyRange, entityName, slicePredicate, ConsistencyLevel.ONE)
        columnMap.remove(startKey)
      }
      println("Finished paging. Total count: "+counter)
    }

    /**
     * Select fields from rows and pass to the supplied function.
     */
    def selectRows(uuids:Array[String], entityName:String, fields:Array[String], proc:((Map[String,String])=>Unit)) {
       val selector = Pelops.createSelector(DAO.poolName, keyspace)
       var slicePredicate = new SlicePredicate
       slicePredicate.setColumn_names(fields.toList.map(_.getBytes))

       //retrieve the columns
       var columnMap = selector.getColumnsFromRows(uuids.toList, entityName, slicePredicate, ConsistencyLevel.ONE)

       //write them out to the output stream
       val keys = List(columnMap.keySet.toArray : _*)

       for(key<-keys){
         val columnsList = columnMap.get(key)
         val fieldValues = columnsList.map(column => (new String(column.name),new String(column.value))).toArray
         val map = scala.collection.mutable.Map.empty[String,String]
         for(fieldValue <-fieldValues){
           map(fieldValue._1) = fieldValue._2
         }
         proc(map.toMap)
       }
     }

    /**
     * Convert a set of cassandra columns into a key-value pair map.
     */
    protected def columnList2Map(columnList:java.util.List[Column]) : Map[String,String] = {
        val tuples = {
            for(column <- columnList)
                yield (new String(column.name), new String(column.value))
        }
        //convert the list
        Map(tuples map {s => (s._1, s._2)} : _*)
    }

    /**
     * Convienience method for accessing values.
     */
    protected def getColumn(uuid:String, columnFamily:String, columnName:String): Option[Column] = {
        try {
            val selector = Pelops.createSelector(DAO.poolName, keyspace)
            Some(selector.getColumnFromRow(uuid, columnFamily, columnName.getBytes, ConsistencyLevel.ONE))
        } catch {
            case _ => None //expected behaviour when row doesnt exist
        }
    }
}