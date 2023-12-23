import App from "./App";
import React from 'react';
import {createRoot} from "react-dom/client";

const appElement = document.getElementById("app")
const root = createRoot(appElement)
root.render(
    <React.StrictMode>
        <App/>
    </React.StrictMode>
)