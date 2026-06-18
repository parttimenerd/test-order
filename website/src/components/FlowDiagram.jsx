import React from 'react';
import Mermaid from '@theme/Mermaid';

const CHART = `flowchart LR
    A["① Instrument once\\nmvn test-order:prepare"] --> B["② Run your tests\\nmvn test"]
    B --> C["③ Reorder by score\\nmvn test-order:auto"]
    B -->|collects| D[(.test-order/\\ntest-deps.lz4)]
    B -->|collects| E[(.test-order/\\nstate.lz4)]
    D -->|informs| C
    E -->|informs| C
    classDef store fill:#e8f4fd,stroke:#2d6a9f,color:#1a3d5c
    class D,E store`;

export default function FlowDiagram() {
  return <Mermaid value={CHART} />;
}
