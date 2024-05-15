import React, { useState, useEffect } from 'react';
import * as am4core from '@amcharts/amcharts4/core';
import * as am4charts from '@amcharts/amcharts4/charts';
import am4themes_animated from '@amcharts/amcharts4/themes/animated';

am4core.useTheme(am4themes_animated);

function Bar({ data, categoryField, valueField }) {

	const [truncate, setTruncate] = useState(false);

	useEffect(() => {

		var chart = am4core.create("bar-chart", am4charts.XYChart);

		if (truncate) {
			chart.data = data.filter((_item, index) => index < 100);
		} else {
			chart.data = data;
		}

		var categoryAxis = chart.xAxes.push(new am4charts.CategoryAxis());
		categoryAxis.title.text = categoryField;
		categoryAxis.dataFields.category = categoryField;
		categoryAxis.renderer.grid.template.location = 0;
		categoryAxis.renderer.minGridDistance = 30;
		categoryAxis.renderer.labels.template.horizontalCenter = "right";
		categoryAxis.renderer.labels.template.verticalCenter = "middle";
		categoryAxis.renderer.labels.template.rotation = 270;
		categoryAxis.tooltip.disabled = true;
		categoryAxis.renderer.minHeight = 110;

		var valueAxis = chart.yAxes.push(new am4charts.ValueAxis());
		valueAxis.title.text = valueField;
		const minimumValue = Math.min(...data.map(item => item[valueField]));
		valueAxis.min = minimumValue;
		valueAxis.renderer.minWidth = 50;

		var series = chart.series.push(new am4charts.ColumnSeries());
		series.sequencedInterpolation = true;
		series.dataFields.valueY = valueField;
		series.dataFields.categoryX = categoryField;
		series.tooltip.pointerOrientation = "vertical";
		series.columns.template.tooltipText = categoryField + ": [bold]{categoryX}[/]\n" + valueField + ": [bold]{valueY}[/]";
		series.columns.template.strokeWidth = 0;
		series.columns.template.column.fillOpacity = 0.8;

		var hoverState = series.columns.template.column.states.create("hover");
		hoverState.properties.cornerRadiusTopLeft = 0;
		hoverState.properties.cornerRadiusTopRight = 0;
		hoverState.properties.fillOpacity = 1;

		chart.cursor = new am4charts.XYCursor();

		chart.mouseWheelBehavior = "zoomX";

		chart.scrollbarX = new am4core.Scrollbar();
		chart.scrollbarY = new am4core.Scrollbar();

		chart.logo.disabled = true;

		return () => {
			chart.dispose();
		}

	}, [data, categoryField, valueField, truncate]);

	return (
		<>
			<div id="bar-chart" style={{ width: "100%", height: "600px" }}></div>
			<div style={{ width: "100%", display: "flex", alignItems: "center", justifyContent: "center", gap: "20px", backgroundColor: "#ccc", paddingTop: "10px", borderRadius: "10px" }}>
				<p><strong>Can't see your chart/seeing too much?</strong> Try adding a filter, a limit, or press TRUNCATE to enforce the proposed cardinality limit: <strong>100</strong></p>
				{truncate ? <button className='btn red-btn' onClick={() => setTruncate(false)}>USE ALL DATA</button> : <button className='btn' onClick={() => setTruncate(true)}>TRUNCATE</button>}
			</div>
		</>
	);
}

export default Bar;