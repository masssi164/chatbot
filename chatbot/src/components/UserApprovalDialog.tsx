import "./UserApprovalDialog.css";

export interface ApprovalRequestData {
  approvalRequestId: string;
  serverLabel: string;
  toolName: string;
  arguments?: string;
}

interface UserApprovalDialogProps {
  request: ApprovalRequestData;
  onApprove: () => void;
  onDeny: () => void;
}

export function UserApprovalDialog({
  request,
  onApprove,
  onDeny,
}: UserApprovalDialogProps) {

  let parsedArgs: Record<string, unknown> | null = null;
  try {
    if (request.arguments) {
      parsedArgs = JSON.parse(request.arguments) as Record<string, unknown>;
    }
  } catch {
    // Ignore parsing errors
  }

  return (
    <div className="user-approval-dialog">
      <div className="approval-header">
        <span className="approval-icon">🔔</span>
        <h3>Bestätigung erforderlich</h3>
      </div>

      <div className="approval-body">
        <div className="approval-info">
          <div className="approval-field">
            <span className="approval-label">Tool:</span>
            <span className="approval-value">{request.toolName}</span>
          </div>
          <div className="approval-field">
            <span className="approval-label">Server:</span>
            <span className="approval-value">{request.serverLabel}</span>
          </div>
        </div>

        {parsedArgs && (
          <div className="approval-arguments">
            <div className="approval-label">Argumente:</div>
            <pre className="approval-json">
              {JSON.stringify(parsedArgs, null, 2)}
            </pre>
          </div>
        )}

      </div>

      <div className="approval-actions">
        <button
          type="button"
          className="approval-button approval-deny"
          onClick={onDeny}
        >
          Ablehnen
        </button>
        <button
          type="button"
          className="approval-button approval-approve"
          onClick={onApprove}
        >
          Genehmigen
        </button>
      </div>
    </div>
  );
}
