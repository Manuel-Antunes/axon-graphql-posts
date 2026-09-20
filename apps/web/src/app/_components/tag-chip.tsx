import { Badge } from "@/components/ui/badge";
import { getFragmentData, graphql, type FragmentType } from "@/gql";

/**
 * O menor componente com dados deste projeto — e por isso o melhor exemplo do padrão.
 *
 * Ele declara o fragmento do que precisa (`id`, `name`) e recebe `FragmentType<...>`, um tipo OPACO:
 * mesmo que a query da página tenha trazido o post inteiro, o TypeScript recusa este componente ler
 * qualquer outro campo. O over-fetching deixa de ser algo a notar em code review e passa a ser erro
 * de compilação.
 */
export const TagChip_tag = graphql(`
    fragment TagChip_tag on Tag {
        id
        name
    }
`);

export function TagChip({ tag }: { tag: FragmentType<typeof TagChip_tag> }) {
    const { name } = getFragmentData(TagChip_tag, tag);
    return (
        <Badge variant="secondary" className="font-mono text-[11px]">
            #{name}
        </Badge>
    );
}
