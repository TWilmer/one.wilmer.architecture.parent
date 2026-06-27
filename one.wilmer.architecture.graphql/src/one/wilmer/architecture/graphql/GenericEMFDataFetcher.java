package one.wilmer.architecture.graphql;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EAttribute;

public class GenericEMFDataFetcher implements DataFetcher<Object> {

    @Override
    public Object get(DataFetchingEnvironment env) {
        // 1. Get the parent EObject (the source of the field)
        EObject source = env.getSource();
        
        // 2. Get the name of the GraphQL field being queried
        String fieldName = env.getField().getName();
        
        // 3. Find the corresponding feature in the EMF model
        var feature = source.eClass().getEStructuralFeature(fieldName);
        
        if (feature == null) return null;

        // 4. Use EMF Reflection to get the value
        Object value = source.eGet(feature);

        // 5. GraphQL lists are naturally handled if the feature is Many
        return value;
    }
}