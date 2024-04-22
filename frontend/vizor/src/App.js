import logo from './logo.svg';
import './App.css';
import React from 'react';
import LoginPage from './pages/LoginPage';
import Home from './pages/Home';
import VizFirst from './pages/VizFirst';
import {BrowserRouter as Router, Route, Routes} from 'react-router-dom';

function App() {
  return (
    
    <div>
      <Router>
          <Routes>
            <Route path="/" element={<LoginPage />} />
            <Route path="/home" element={<Home />} />
            <Route path="/vizfirst" element={<VizFirst />} />
          </Routes>
        </Router>
    </div>

  );
}

export default App;
