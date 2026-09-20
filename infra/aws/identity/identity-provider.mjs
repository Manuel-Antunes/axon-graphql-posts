export const handler = async (event) => {
  event.response = {
    claimsOverrideDetails: {
      claimsToAddOrOverride: {
        identity_provider: 'cognito',
      },
    },
  };
  return event;
};
