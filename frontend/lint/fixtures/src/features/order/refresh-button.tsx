import { useQueryClient } from "@tanstack/react-query";

export function RefreshButton({ label }: { label: string }) {
  const queryClient = useQueryClient();

  return (
    <button
      type="button"
      onClick={() => {
        void queryClient.invalidateQueries({ queryKey: ["orders"] });
      }}
    >
      {label}
    </button>
  );
}
