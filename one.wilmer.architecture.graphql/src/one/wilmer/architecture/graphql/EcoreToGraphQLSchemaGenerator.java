package one.wilmer.architecture.graphql;

import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.FieldCoordinates;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.idl.SchemaPrinter;
import graphql.schema.GraphQLOutputType;
import graphql.Scalars;
import graphql.schema.*;
import org.eclipse.emf.ecore.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;

public class EcoreToGraphQLSchemaGenerator {

	private final Map<EClass, GraphQLObjectType> cache = new HashMap<>();

	public GraphQLSchema generate(EPackage ePackage, ModelLoader modelLoader) {
		GraphQLObjectType.Builder queryBuilder = GraphQLObjectType.newObject().name("Query");

		GraphQLCodeRegistry.Builder codeRegistry = GraphQLCodeRegistry.newCodeRegistry();
		DataFetcher<Object> genericDataFetcher = new GenericEMFDataFetcher();

		// 1. Identify Exported Classes
		List<EClass> exportedClasses = ePackage.getEClassifiers().stream().filter(c -> c instanceof EClass)
				.map(c -> (EClass) c).filter(this::isExported) // Checks for the annotation or OslcExported supertype
				.collect(Collectors.toList());

		DataFetcher<Object> rootDataFetcher = env -> {
			String fieldName = env.getField().getName();
			String targetName = fieldName.endsWith("s") ? fieldName.substring(0, fieldName.length() - 1) : fieldName;

			EClassifier requestedClassifier = ePackage.getEClassifiers().stream()
					.filter(c -> c.getName().equalsIgnoreCase(targetName) || c.getName().equalsIgnoreCase(fieldName))
					.findFirst().orElse(null);

			if (requestedClassifier == null) {
				return null;
			}

			List<EObject> allObjects = modelLoader.getAllObjectsOfType(requestedClassifier);

			// Argument handling (ID/Name)
			Map<String, Object> arguments = env.getArguments();
			String identifier = null;
			if (arguments.containsKey("id") && arguments.get("id") != null)
				identifier = arguments.get("id").toString();
			else if (arguments.containsKey("name") && arguments.get("name") != null)
				identifier = arguments.get("name").toString();
			else if (arguments.containsKey("key") && arguments.get("key") != null)
				identifier = arguments.get("key").toString();

			if (identifier != null) {
				final String targetId = identifier;
				EObject matchedObject = allObjects.stream().filter(obj -> {
					EStructuralFeature idFeature = obj.eClass().getEStructuralFeature("id");
					if (idFeature == null)
						idFeature = obj.eClass().getEStructuralFeature("name");
					if (idFeature == null)
						idFeature = obj.eClass().getEStructuralFeature("key");
					if (idFeature != null) {
						Object val = obj.eGet(idFeature);
						return val != null && val.toString().equals(targetId);
					}
					return false;
				}).findFirst().orElse(null);

				return fieldName.endsWith("s") ? (matchedObject != null ? List.of(matchedObject) : List.of())
						: matchedObject;
			}
			return allObjects;
		};

		// 2. Build GraphQL Types
		List<GraphQLObjectType> exportedGraphQLTypes = new ArrayList<>();

		// Expose classes and Wire the fetchers dynamically
		for (EClassifier classifier : ePackage.getEClassifiers()) {
			if (classifier instanceof EClass eClass) {
				String baseName = eClass.getName().toLowerCase();
				GraphQLObjectType objectType = convertEClass(eClass, codeRegistry, genericDataFetcher);

				if (exportedClasses.contains(eClass)) {
					exportedGraphQLTypes.add(objectType);
				}

				// Plural List Query
				queryBuilder.field(GraphQLFieldDefinition.newFieldDefinition().name(baseName + "s")
						.type(new GraphQLList(objectType)).build());
				codeRegistry.dataFetcher(FieldCoordinates.coordinates("Query", baseName + "s"), rootDataFetcher);

				// Singular Item Query
				queryBuilder.field(GraphQLFieldDefinition.newFieldDefinition().name(baseName).type(objectType)
						.argument(GraphQLArgument.newArgument().name("id").type(Scalars.GraphQLString).build())
						.argument(GraphQLArgument.newArgument().name("name").type(Scalars.GraphQLString).build())
						.argument(GraphQLArgument.newArgument().name("key").type(Scalars.GraphQLString).build())
						.build());
				codeRegistry.dataFetcher(FieldCoordinates.coordinates("Query", baseName), rootDataFetcher);
			}
		}
		
		// 1. Create the formal definition for @key(fields: String!)
		GraphQLDirective keyDirectiveDef = GraphQLDirective.newDirective()
		        .name("key")
		        .argument(GraphQLArgument.newArgument()
		                .name("fields")
		                .type(new GraphQLNonNull(Scalars.GraphQLString))
		                .build())
		        .validLocations(
		                graphql.introspection.Introspection.DirectiveLocation.OBJECT,
		                graphql.introspection.Introspection.DirectiveLocation.INTERFACE)
		        .build();

		// 3. Inject Apollo Federation Requirements
		injectFederation(queryBuilder, codeRegistry, exportedClasses, exportedGraphQLTypes, ePackage, modelLoader);

		return GraphQLSchema.newSchema().query(queryBuilder.build()).codeRegistry(codeRegistry.build())   .additionalDirective(keyDirectiveDef).build();
	}

	private void injectFederation(GraphQLObjectType.Builder queryBuilder, GraphQLCodeRegistry.Builder codeRegistry,
			List<EClass> exportedClasses, List<GraphQLObjectType> exportedGraphQLTypes, EPackage ePackage,
			ModelLoader modelLoader) {

// Custom Scalar for _Any (used in _entities representations)
		// Custom Scalar for _Any (used in _entities representations)
		GraphQLScalarType anyScalar = GraphQLScalarType.newScalar()
		        .name("_Any")
		        .description("Federation representation scalar")
		        .coercing(new graphql.schema.Coercing<Object, Object>() {
		            @Override
		            public Object serialize(Object dataFetcherResult) {
		                return dataFetcherResult;
		            }

		            @Override
		            public Object parseValue(Object input) {
		                // When Cosmo Router sends representations as JSON variables, 
		                // graphql-java already converted them to a Java Map.
		                return input; 
		            }

		            @Override
		            public Object parseLiteral(Object input) {
		                // When you type the representation inline in the query string (like your test),
		                // graphql-java parses it into an AST ObjectValue. We must map it manually.
		                if (input instanceof graphql.language.ObjectValue) {
		                    java.util.Map<String, Object> map = new java.util.HashMap<>();
		                    for (graphql.language.ObjectField field : ((graphql.language.ObjectValue) input).getObjectFields()) {
		                        graphql.language.Value<?> value = field.getValue();
		                        
		                        // Extract the raw value from the AST node
		                        if (value instanceof graphql.language.StringValue) {
		                            map.put(field.getName(), ((graphql.language.StringValue) value).getValue());
		                        } else if (value instanceof graphql.language.IntValue) {
		                            map.put(field.getName(), ((graphql.language.IntValue) value).getValue());
		                        }
		                        // Add more AST types here if your @key ever uses booleans or floats
		                    }
		                    return map;
		                }
		                throw new graphql.schema.CoercingParseLiteralException("Expected AST ObjectValue for _Any scalar");
		            }
		        })
		        .build();

// The _Entity Union Type
		GraphQLUnionType.Builder entityUnionBuilder = GraphQLUnionType.newUnionType().name("_Entity");
		for (GraphQLObjectType exportedType : exportedGraphQLTypes) {
			entityUnionBuilder.possibleType(exportedType);
		}
		GraphQLUnionType entityUnion = entityUnionBuilder.build();

// Add _entities Query
		queryBuilder
				.field(GraphQLFieldDefinition.newFieldDefinition().name("_entities")
						.type(new GraphQLNonNull(new GraphQLList(entityUnion)))
						.argument(GraphQLArgument.newArgument().name("representations")
								.type(new GraphQLNonNull(new GraphQLList(new GraphQLNonNull(anyScalar)))).build())
						.build());

// Add _service Query
		GraphQLObjectType serviceType = GraphQLObjectType.newObject().name("_Service").field(GraphQLFieldDefinition
				.newFieldDefinition().name("sdl").type(new GraphQLNonNull(Scalars.GraphQLString)).build()).build();

		queryBuilder.field(GraphQLFieldDefinition.newFieldDefinition().name("_service")
				.type(new GraphQLNonNull(serviceType)).build());

// --- FEDERATION DATA FETCHERS ---

// _entities DataFetcher
		DataFetcher<Object> entitiesFetcher = env -> {
			List<Map<String, Object>> representations = env.getArgument("representations");
			List<EObject> resolvedEntities = new ArrayList<>();

			for (Map<String, Object> rep : representations) {
				String typeName = (String) rep.get("__typename");
				String key = (String) rep.get("key");

				EClassifier classifier = ePackage.getEClassifier(typeName);
				if (classifier instanceof EClass) {
// Logic to find the specific EObject by its key
					EObject matched = modelLoader.getAllObjectsOfType(classifier).stream().filter(obj -> {
						EStructuralFeature keyFeature = obj.eClass().getEStructuralFeature("key");
						return keyFeature != null && key.equals(obj.eGet(keyFeature));
					}).findFirst().orElse(null);

					if (matched != null)
						resolvedEntities.add(matched);
				}
			}
			return resolvedEntities;
		};
		codeRegistry.dataFetcher(FieldCoordinates.coordinates("Query", "_entities"), entitiesFetcher);

// Type Resolver for the _Entity Union (Tells GraphQL which object type a resolved EObject is)
		codeRegistry.typeResolver("_Entity", env -> {
			EObject eObject = (EObject) env.getObject();
			return env.getSchema().getObjectType(eObject.eClass().getName());
		});

// _service DataFetcher (Lazily prints the schema)
		DataFetcher<Object> serviceFetcher = env -> {
// SchemaPrinter ignores runtime federation fields to avoid circular logic
			SchemaPrinter.Options options = SchemaPrinter.Options.defaultOptions()
					.includeDirectives(true)
					.includeDirectiveDefinitions(true);
			String sdl = new SchemaPrinter(options).print(env.getGraphQLSchema());
			return Map.of("sdl", sdl);
		};
		codeRegistry.dataFetcher(FieldCoordinates.coordinates("Query", "_service"), serviceFetcher);
	}

	private GraphQLObjectType convertEClass(EClass eClass, GraphQLCodeRegistry.Builder codeRegistry,
			DataFetcher<?> genericDataFetcher) {
		// 1. If it's already in the cache, return it (this handles cycles)
		if (cache.containsKey(eClass))
			return (GraphQLObjectType) cache.get(eClass);

		// 2. Create the builder
		 GraphQLObjectType.Builder typeBuilder = GraphQLObjectType.newObject().name(eClass.getName());

		  // Apply Federation @key directive if exported
		// Inside convertEClass...
		 if (isExported(eClass)) {
		     typeBuilder.withDirective(GraphQLDirective.newDirective()
		             .name("key")
		             .argument(GraphQLArgument.newArgument()
		                     .name("fields")
		                     .type(Scalars.GraphQLString)
		                     .value("key") 
		                     .build())
		             .build());
		 }
        
		
		// 3. IMPORTANT: Put a dummy/placeholder in the cache immediately to satisfy
		// references
		// Note: You need to change the cache map to hold GraphQLObjectType.Builder or
		// handle this carefully
		// To simplify: create the object, then populate it.

		// Let's perform the population:
		for (EAttribute attr : eClass.getEAllAttributes()) {
			typeBuilder.field(GraphQLFieldDefinition.newFieldDefinition().name(attr.getName())
					.type(mapToGraphQLScalar(attr.getEAttributeType())).build());
			codeRegistry.dataFetcher(FieldCoordinates.coordinates(eClass.getName(), attr.getName()),
					genericDataFetcher);
		}

		for (EReference ref : eClass.getEAllReferences()) {
			// Use typeRef to handle the cycle
			GraphQLOutputType refType = GraphQLTypeReference.typeRef(ref.getEReferenceType().getName());

			typeBuilder.field(GraphQLFieldDefinition.newFieldDefinition().name(ref.getName())
					.type(ref.isMany() ? new GraphQLList(refType) : refType).build());
			codeRegistry.dataFetcher(FieldCoordinates.coordinates(eClass.getName(), ref.getName()), genericDataFetcher);
		}

		GraphQLObjectType type = typeBuilder.build();
		cache.put(eClass, type); // Now store the fully built object
		return type;
	}
	

    private boolean isExported(EClass eClass) {
    	
    	for( EAttribute attribute : eClass.getEAllAttributes())
    	{
    		System.out.println("Look at "+eClass.getName()+ " attribute:"+attribute.getName());
    		if( attribute.getName().equals("key")) {
    			return true;
    		}
    			
    	}
    	return false;

    }

	private GraphQLScalarType mapToGraphQLScalar(EDataType dataType) {
		return switch (dataType.getInstanceTypeName()) {
		case "int", "java.lang.Integer" -> Scalars.GraphQLInt;
		case "boolean", "java.lang.Boolean" -> Scalars.GraphQLBoolean;
		default -> Scalars.GraphQLString;
		// Note: Your Xtext Enum (AnEnum) will fall through to GraphQLString.
		// The GraphQL engine will automatically call toString() on the EMF Enumerator,
		// serializing it perfectly.
		};
	}
}