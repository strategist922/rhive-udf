package com.sonamine.hive.udf;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDFArgumentTypeException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.udf.generic.AbstractGenericUDAFResolver;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDAFEvaluator;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StandardMapObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;

/**
 * Convert two aggregated columns into a key-value map.
 * 
 * The key must be a primitive type (int, boolean, float, string, ...) and the 
 * value may be a primitive or a complex type (structs, maps, arrays).
 * 
 * @see https://cwiki.apache.org/Hive/genericudafcasestudy.html
 */
@Description(name = "to_map", value = "_FUNC_(key, value) - Convert two aggregated columns into a key-value map")
public class UDAFToMap extends AbstractGenericUDAFResolver {

	static final Log LOG = LogFactory.getLog(UDAFToMap.class.getName());

	public GenericUDAFEvaluator getEvaluator(TypeInfo[] parameters)
			throws SemanticException {

		if (parameters.length != 2) {
			throw new UDFArgumentTypeException(parameters.length - 1,
					"Exactly one argument is expected.");
		}

		if (parameters[0].getCategory() != ObjectInspector.Category.PRIMITIVE) {
			throw new UDFArgumentTypeException(0,
					"Only primitive type arguments are accepted for the key but "
							+ parameters[0].getTypeName()
							+ " was passed as parameter 1.");
		}

		return new UDAFToMapEvaluator();
	}

	public static class UDAFToMapEvaluator extends GenericUDAFEvaluator {

		protected PrimitiveObjectInspector inputKeyOI;
		protected ObjectInspector inputValueOI;
		protected StandardMapObjectInspector loi;

		protected StandardMapObjectInspector internalMergeOI;

		public ObjectInspector init(Mode m, ObjectInspector[] parameters)
                throws HiveException {
            super.init(m, parameters);
            if (m == Mode.PARTIAL1) {
                inputKeyOI = (PrimitiveObjectInspector) parameters[0];
                inputValueOI = (ObjectInspector) parameters[1];
                return ObjectInspectorFactory.getStandardMapObjectInspector(
                		(PrimitiveObjectInspector) ObjectInspectorUtils.getStandardObjectInspector(inputKeyOI), 
                		(ObjectInspector) ObjectInspectorUtils.getStandardObjectInspector(inputValueOI));
            } else {
                if (!(parameters[0] instanceof StandardMapObjectInspector)) {
                    inputKeyOI = (PrimitiveObjectInspector) parameters[0];
                    inputValueOI = (ObjectInspector) parameters[1];
                    return ObjectInspectorFactory.getStandardMapObjectInspector(
                    		(PrimitiveObjectInspector) ObjectInspectorUtils.getStandardObjectInspector(inputKeyOI), 
                    		(ObjectInspector) ObjectInspectorUtils.getStandardObjectInspector(inputValueOI));
                } else {
                    internalMergeOI = (StandardMapObjectInspector) parameters[0];
                    inputKeyOI = (PrimitiveObjectInspector) internalMergeOI.getMapKeyObjectInspector();
                    inputValueOI = (ObjectInspector) internalMergeOI.getMapValueObjectInspector();
                    loi = (StandardMapObjectInspector) ObjectInspectorUtils.getStandardObjectInspector(internalMergeOI);                 
                    return loi;
                }
            }
        }

		static class MkMapAggregationBuffer implements AggregationBuffer {
			Map<Object,Object> container;
		}

		public void reset(AggregationBuffer agg) throws HiveException {
			((MkMapAggregationBuffer) agg).container = new HashMap<Object,Object>(144);
		}

		public AggregationBuffer getNewAggregationBuffer() throws HiveException {
			MkMapAggregationBuffer ret = new MkMapAggregationBuffer();
			reset(ret);
			return ret;
		}

		public void iterate(AggregationBuffer agg, Object[] parameters)
				throws HiveException {
			assert (parameters.length == 2);
			Object key = parameters[0];
			Object value = parameters[1];

			if (key != null) {
				MkMapAggregationBuffer myagg = (MkMapAggregationBuffer) agg;
				putIntoMap(key, value, myagg);
			}
		}

		public Object terminatePartial(AggregationBuffer agg)
				throws HiveException {
			MkMapAggregationBuffer myagg = (MkMapAggregationBuffer) agg;
			return myagg.container;
		}

		public void merge(AggregationBuffer agg, Object partial)
				throws HiveException {
			MkMapAggregationBuffer myagg = (MkMapAggregationBuffer) agg;
			Map<Object,Object> partialResult = (Map<Object,Object>) internalMergeOI.getMap(partial);
			for (Map.Entry<Object,Object> entry: partialResult.entrySet()) {
				putIntoMap(entry.getKey(), entry.getValue(), myagg);
			}
		}

		public Object terminate(AggregationBuffer agg) throws HiveException {
			MkMapAggregationBuffer myagg = (MkMapAggregationBuffer) agg;
			return myagg.container;
		}

		protected void putIntoMap(Object key, Object value, MkMapAggregationBuffer myagg) {
			Object pKeyCopy = ObjectInspectorUtils.copyToStandardObject(key, this.inputKeyOI);
			Object pValueCopy = ObjectInspectorUtils.copyToStandardObject(value, this.inputValueOI);
			myagg.container.put(pKeyCopy, pValueCopy);
		}
	}

}
