import { createRoot } from "react-dom/client";

function App() {
  return <h1>Hello Harudle</h1>;
}

const root = document.getElementById("root");

if (root) {
  createRoot(root).render(<App />);
}
