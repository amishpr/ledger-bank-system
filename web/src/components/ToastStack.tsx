import type { Toast } from "../useToasts";

export function ToastStack({ toasts, onDismiss }: { toasts: Toast[]; onDismiss: (id: string) => void }) {
  if (toasts.length === 0) return null;
  return (
    <div className="toast-stack">
      {toasts.map((toast) => (
        <div key={toast.id} className={`toast toast-${toast.kind}`} onClick={() => onDismiss(toast.id)}>
          {toast.message}
        </div>
      ))}
    </div>
  );
}
