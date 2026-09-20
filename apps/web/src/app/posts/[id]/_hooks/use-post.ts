'use client';

import { useMutation, useSuspenseQuery } from '@apollo/client/react';

import { graphql } from '@/gql';

import { PostByIdQuery } from '../query';

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
