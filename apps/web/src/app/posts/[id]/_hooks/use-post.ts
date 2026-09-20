'use client';

import { useMutation, useSuspenseQuery } from '@apollo/client/react';

import { graphql } from '@/gql';

import { PostByIdQuery } from '../query';

/**
 * `updatePost` devolve o post projetado, e o cache o normaliza pelo `id` — então a tela se atualiza
 * sozinha, sem `refetch`. É o que a identidade por id compra: o mesmo objeto, um lugar só.
 */
const UpdatePostMutation = graphql(`
  mutation UpdatePost($input: UpdatePostInput!) {
    updatePost(input: $input) {
      id
      version
      ...PostArticle_post
      ...PostEditor_post
    }
  }
`);

/**
 * A exclusão é LÓGICA (`@SQLDelete` + `@SQLRestriction`): a linha e o stream continuam lá, e por isso
 * `restorePost` funciona — ele reidrata o agregado dos eventos. O retorno é `Boolean`, então o cache
 * não tem o que normalizar: quem refaz a leitura é o `refetchQueries`.
 */
const DeletePostMutation = graphql(`
  mutation DeletePost($id: ID!) {
    deletePost(id: $id)
  }
`);

const RestorePostMutation = graphql(`
  mutation RestorePost($id: ID!) {
    restorePost(id: $id) {
      id
      version
      ...PostArticle_post
      ...PostEditor_post
    }
  }
`);

export function usePost(id: string) {
  const { data, error, refetch } = useSuspenseQuery(PostByIdQuery, {
    variables: { id },
    errorPolicy: 'all',
  });

  const [updatePost, updateState] = useMutation(UpdatePostMutation);
  const [deletePost, deleteState] = useMutation(DeletePostMutation, {
    refetchQueries: ['PostById', 'FeedPosts'],
  });
  const [restorePost, restoreState] = useMutation(RestorePostMutation, {
    refetchQueries: ['FeedPosts'],
  });

  return {
    post: data?.post,
    error,
    refetch,
    update: { run: updatePost, ...updateState },
    remove: { run: deletePost, ...deleteState },
    restore: { run: restorePost, ...restoreState },
  };
}
