import { useEffect, type PropsWithChildren } from "react";

interface ModalProps {
  title?: string;
  onClose: () => void;
}

function Modal({ title, onClose, children }: PropsWithChildren<ModalProps>) {
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [onClose]);

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onClick={() => onClose()}
    >
      <div
        className="modal-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={title ?? "Modal"}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="modal-header">
          {title ? <h2>{title}</h2> : <span />}
          <button
            type="button"
            className="modal-close"
            onClick={onClose}
            aria-label="Close"
          >
            ×
          </button>
        </div>
        <div className="modal-content">{children}</div>
      </div>
    </div>
  );
}

export default Modal;
