/** The Vertex mark: a node with one trace leaving it. The trace draws on hover. */
export function Mark() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" fill="none">
      <rect x="1" y="1" width="22" height="22" rx="2" fill="currentColor" />
      <path d="M4 15 H8 L10 9 L13 18 L15 12 H20" stroke="#1d4bff" strokeWidth="2" strokeLinejoin="round" />
      <path
        className="wordmark__pulse"
        d="M4 15 H8 L10 9 L13 18 L15 12 H20"
        stroke="#9fb8ff"
        strokeWidth="2"
        strokeLinejoin="round"
      />
    </svg>
  );
}
