package one.wilmer.architecture.graphql;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import one.wilmer.architecture.archSpec.ArchSpecPackage;

public class ArchitectureGraphQLEngine {

    private GraphQL graphQL;

    public ArchitectureGraphQLEngine(ModelLoader modelLoader) {
        // Pass the ModelLoader into the generator so it can wire the fetchers directly
        GraphQLSchema schema = new EcoreToGraphQLSchemaGenerator()
                .generate(ArchSpecPackage.eINSTANCE, modelLoader);

        this.graphQL = GraphQL.newGraphQL(schema).build();
    }

    public Object execute(String query) {
        ExecutionInput executionInput = ExecutionInput.newExecutionInput()
                .query(query)
                .build();

        ExecutionResult executionResult = graphQL.execute(executionInput);

        if (executionResult.getErrors().size() > 0) {
            System.out.println("-> [GraphQL Engine] EXECUTION ERRORS: " + executionResult.getErrors());
        }

        return executionResult.toSpecification();
    }
}