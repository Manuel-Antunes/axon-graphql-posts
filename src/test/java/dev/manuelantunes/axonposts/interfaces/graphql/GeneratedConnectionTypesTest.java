package dev.manuelantunes.axonposts.interfaces.graphql;

import graphql.language.AstPrinter;
import graphql.language.TypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.ConnectionTypeDefinitionConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mantém, no fim do próprio {@code posts.graphqls}, o bloco de tipos de cursor connection que o
 * {@link ConnectionTypeDefinitionConfigurer} do Spring criaria — e prova que declará-los não muda nada.
 *
 * <h2>Por que declarar, se o Spring gera</h2>
 * Porque o schema é lido por mais gente que o Spring. O plugin do IDE valida as queries injetadas nos
 * testes ({@code //language=GraphQL}) contra o SDL estático: sem {@code PostConnection} e companhia, todo
 * {@code edges { node { … } }} vira "Unknown field". O mesmo vale para qualquer cliente que gere código a
 * partir do arquivo. O que o Spring monta em memória não existe para nenhum deles.
 *
 * <h2>Por que gerado, e não escrito à mão</h2>
 * Escrito à mão, o bloco é uma segunda definição de schema que envelhece calada: uma connection nova
 * passaria a exigir que alguém lembrasse de vir aqui, e um upgrade do Spring que mudasse a forma dos tipos
 * não apareceria em lugar nenhum. Aqui não há segunda definição — o bloco sai do <b>próprio configurer do
 * Spring</b>, rodando sobre a parte escrita à mão deste mesmo arquivo.
 *
 * <h2>Por que isso é seguro em runtime</h2>
 * O configurer só acrescenta o que falta: {@code filter(name -> registry.getType(name).isEmpty())}. Com o
 * bloco no lugar ele não faz nada, e o schema efetivo é o mesmo — é o que
 * {@link #declaringThemChangesTheSchemaNotAtAll()} verifica, comparando o registry das duas formas.
 * <p>
 * O caso misto também é seguro: se alguém acrescentar um campo {@code …Connection} e esquecer de rodar
 * isto, o Spring gera o que falta na subida. O {@code PageInfo} que ele tentaria redefinir é recusado sem
 * exceção pelo {@code TypeDefinitionRegistry.add}, que devolve o erro em vez de lançá-lo — e o configurer
 * ignora esse retorno, mantendo a definição declarada.
 *
 * <h2>O que acontece quando diverge</h2>
 * O teste <b>reescreve</b> o bloco e falha, dizendo isso. Rodar a suíte uma vez conserta; o commit é a
 * confirmação de que alguém olhou o diff. Só o bloco é reescrito — o que está acima do marcador é seu.
 */
class GeneratedConnectionTypesTest {

    private static final Path SCHEMA = Path.of("src/main/resources/graphql/posts.graphqls");

    private static final String BEGIN = "# >>> GERADO por GeneratedConnectionTypesTest — não editar à mão";

    private static final String END = "# <<< fim do bloco gerado";

    private static final String PREAMBLE = """
            #
            # São os tipos que o ConnectionTypeDefinitionConfigurer do Spring GraphQL criaria sozinho, a partir
            # do sufixo "Connection" dos campos acima. Estão aqui porque o SDL é lido também pelo IDE e por
            # geradores de cliente, para quem o que o Spring monta em memória não existe.
            #
            # Para atualizar: ./mvnw test -Dtest=GeneratedConnectionTypesTest
            """;

    @Test
    void theSchemaCarriesWhatSpringWouldGenerate() throws IOException {
        String schema = Files.readString(SCHEMA);
        String expected = handWrittenPart(schema) + generatedBlock(handWrittenPart(schema));

        if (!expected.equals(schema)) {
            Files.writeString(SCHEMA, expected);
        }

        assertThat(schema)
                .as("o bloco gerado de %s estava desatualizado e ACABOU DE SER REESCRITO — confira o diff", SCHEMA)
                .isEqualTo(expected);
    }

    /**
     * A garantia que torna o bloco inofensivo: o schema com ele declarado e o schema sem ele, passados os
     * dois pelo configurer (como acontece na subida), descrevem exatamente os mesmos tipos.
     */
    @Test
    void declaringThemChangesTheSchemaNotAtAll() throws IOException {
        String schema = Files.readString(SCHEMA);

        String withBlock = typesOf(schema);
        String withoutBlock = typesOf(handWrittenPart(schema));

        assertThat(withBlock).isEqualTo(withoutBlock);
    }

    /** Tudo o que vem antes do marcador: a parte do arquivo que é escrita à mão. */
    private static String handWrittenPart(String schema) {
        int marker = schema.indexOf(BEGIN);
        return (marker < 0 ? schema : schema.substring(0, marker)).stripTrailing() + "\n\n";
    }

    private static String generatedBlock(String handWritten) {
        return BEGIN + "\n" + PREAMBLE + "\n" + newTypesFrom(handWritten) + END + "\n";
    }

    /**
     * Roda o configurer do Spring sobre o SDL recebido e devolve o que ele acrescentou. Ordenado por nome
     * porque o resultado vai para o versionamento: a ordem de descoberta do registry é detalhe de
     * implementação, e um diff espúrio a cada refactor do schema seria ruído.
     */
    private static String newTypesFrom(String sdl) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(sdl);
        Set<String> declared = Set.copyOf(registry.types().keySet());

        new ConnectionTypeDefinitionConfigurer().configure(registry);

        return registry.types().values().stream()
                .filter(type -> !declared.contains(type.getName()))
                .sorted(Comparator.comparing(TypeDefinition::getName))
                .map(AstPrinter::printAst)
                .collect(Collectors.joining("\n\n", "", "\n\n"));
    }

    /** Todos os tipos do SDL depois do configurer, normalizados para comparação. */
    private static String typesOf(String sdl) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(sdl);
        new ConnectionTypeDefinitionConfigurer().configure(registry);

        return registry.types().values().stream()
                .sorted(Comparator.comparing(TypeDefinition::getName))
                .map(AstPrinter::printAst)
                .collect(Collectors.joining("\n"));
    }
}
