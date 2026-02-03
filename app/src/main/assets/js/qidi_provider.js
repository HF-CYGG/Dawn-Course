/**
 * 基于模板_全流程
 * @version 0.5.e769f1a
 * @param { Document | string } iframeContent 获取的网页元素
 * @param { Document | string } frameContent 获取的网页元素
 * @param { Document | string } dom 获取的网页元素
 * @returns { Promise< string > } 包含courses和timetable, 导出给parser.js和timer.js, parser.js和timer.js不做处理, 仅转发
 */
async function scheduleHtmlProvider(iframeContent = "", frameContent = "", dom = document) { // todo: 重复点击导入按钮

  /* 输出提示栏 */
  let tryLogFrame;
  try {
    tryLogFrame = await newLogFrame();

    if (typeof tryLogFrame?.log !== "function" || typeof tryLogFrame?.copyButton !== "function" || typeof tryLogFrame?.repoLink !== "function") {
      console.error("初始化输出提示栏失败, 将不会有输出提示");
      tryLogFrame = undefined;
    }

  } catch (err) {
    console.error(`初始化输出提示栏失败: "${err?.message ?? err}", 将不会有输出提示`);
  }
  const logFrame = tryLogFrame ?? { log: console.log, copyButton: Function(), repoLink: Function() };

  logFrame.log("开始导入<br />");

  /* 页面检测 */
  if (!document.URL.includes("")) {
    logFrame.log("<b>导入失败</b><br />页面不正确<br /><br />", logFrame.repoLink());
    return "do not continue";
  }

  /* 获取课程周数列表 */
  logFrame.log("获取课程周数列表<br />");

  let tryDrpWeeks;
  try {
    const iframeElement = document.querySelector("iframe#f_1");
    const coursesDocument = iframeElement?.contentDocument ?? iframeElement?.contentWindow?.document ?? document;
    const drpWeeksElement = coursesDocument?.querySelector("#drpWeeks");
    tryDrpWeeks = Array.from(drpWeeksElement.options).map(e => parseInt(e.value)).filter(v => v !== 0 && v !== NaN).sort((a, b) => a - b);

  } catch (err) {
    logFrame.log(`<b>导入失败</b><br />获取课程周数列表失败: "${err?.message ?? err}", 请确保教务系统已登录<br /><br />`, logFrame.repoLink());
    return "do not continue";
  }
  const drpWeeks = tryDrpWeeks;
  logFrame.log(`获取到课程周数列表, 长度${drpWeeks?.length} `, logFrame.copyButton(drpWeeks), "<br />");

  /* 遍历课程周数列表 */
  let tryCourses, tryTimestamp;
  for (const week of drpWeeks) {

    /* 获取请求参数 */
    let tryVIEWSTATE, tryVIEWSTATEGENERATOR, tryEVENTVALIDATION, tryBtnSearch, tryDrpSemester;
    try {
      const iframeElement = document.querySelector("iframe#f_1");
      const coursesDocument = iframeElement?.contentDocument ?? iframeElement?.contentWindow?.document ?? document;
      
      logFrame.log(`获取第${week}周请求参数<br />`);
      tryVIEWSTATE = coursesDocument.querySelector("#__VIEWSTATE").value;
      tryVIEWSTATEGENERATOR = coursesDocument.querySelector("#__VIEWSTATEGENERATOR").value;
      tryEVENTVALIDATION = coursesDocument.querySelector("#__EVENTVALIDATION").value;
      tryBtnSearch = coursesDocument.querySelector("#btnSearch")?.value ?? "查询";
      tryDrpSemester = coursesDocument.querySelector("#drpSemester").value;

      if (
        typeof tryVIEWSTATE !== "string" ||
        typeof tryVIEWSTATEGENERATOR !== "string" ||
        typeof tryEVENTVALIDATION !== "string" ||
        typeof tryBtnSearch !== "string" ||
        typeof tryDrpSemester !== "string"
      ) {
        logFrame.log(`<b>导入失败</b><br />获取第${week}周请求参数失败, 请确保教务系统已登录<br /><br />`);
        return "do not continue";
      }

    } catch (err) {
      logFrame.log(`<b>导入失败</b><br />获取第${week}周请求参数失败: "${err?.message ?? err}", 请确保教务系统已登录<br /><br />`);
      return "do not continue";
    }
    const VIEWSTATE = tryVIEWSTATE;
    const VIEWSTATEGENERATOR = tryVIEWSTATEGENERATOR;
    const EVENTVALIDATION = tryEVENTVALIDATION;
    const btnSearch = tryBtnSearch;
    const drpSemester = tryDrpSemester;


    /* 获取课程数据 */
    logFrame.log(`获取第${week}周课程数据...<br />`);

    let tryResponse;
    try {
      tryResponse = await fetch("StuCourseSchedule.aspx", { // 相对路径，适应不同域名
        headers: {
          "content-type": "application/x-www-form-urlencoded"
        },
        body: new URLSearchParams({
          "__VIEWSTATE": VIEWSTATE,
          "__VIEWSTATEGENERATOR": VIEWSTATEGENERATOR,
          "__EVENTVALIDATION": EVENTVALIDATION,
          "btnSearch": btnSearch,
          "drpSemester": drpSemester,
          "drpWeeks": week,
        }).toString(),
        method: "POST",
        credentials: "include",
      });

      if (!tryResponse.ok) {
        logFrame.log(`<b>导入失败</b><br />网络请求失败: "${tryResponse.status} ${tryResponse.statusText}", 请确保教务系统已登录<br /><br />`, logFrame.repoLink());
        return "do not continue";
      }

    } catch (err) {
      logFrame.log(`<b>导入失败</b><br />网络请求失败: "${err?.message ?? err}", 请确保教务系统已登录<br /><br />`, logFrame.repoLink());
      return "do not continue";
    }
    const response = tryResponse;

    let tryResponseStr;
    try {
      tryResponseStr = await response.text();

    } catch (err) {
      logFrame.log(`<b>导入失败</b><br />解析响应数据失败: "${err?.message ?? err}", 请确保教务系统已登录<br /><br />`, logFrame.repoLink());
      return "do not continue";
    }
    const responseStr = tryResponseStr;

    logFrame.log(`获取到第${week}周课程数据, 长度${responseStr?.length} `, logFrame.copyButton(responseStr), "<br />");

    /* 识别课程数据 */
    logFrame.log(`识别第${week}周课程数据<br />`);

    tryCourses = tryCourses || []; // 确保初始化
    let tryCoursesOfWeek;
    try {
      tryCoursesOfWeek = [];

      const doc = new DOMParser().parseFromString(responseStr, 'text/html');
      const coursesRaw = doc.querySelector("#dvReport").querySelectorAll(".pkScheduleTime");

      for (const courseRaw of coursesRaw) {
        // console.log(courseRaw)
        if (courseRaw?.innerText?.replace(/\s/g, "") === "") continue;

        const name = courseRaw.childNodes[0]?.nodeValue?.trim() ?? "-";
        const teacher = courseRaw.querySelectorAll(".scheduleWeek")[0]?.innerText.trim() ?? "";
        const position = courseRaw.querySelectorAll(".scheduleWeek")[1]?.innerText.trim() ?? "";
        const idParts = courseRaw.id.match(/node_(\d+)_(\d+)/);
        const day = parseInt(idParts[1]);
        const startSection = parseInt(idParts[2]);
        const rowspan = parseInt(courseRaw.getAttribute("rowspan")) || 1;
        const sections = [];
        for (let i = 0; i < rowspan; i++) {
          sections.push(startSection + i);
        }

        tryCoursesOfWeek.push({
          name: name,
          position: position,
          teacher: teacher,
          weeks: [week],
          day: day,
          sections: sections,
        });
      }

      if (tryCoursesOfWeek === undefined) {
        logFrame.log(`<b>导入失败</b><br />识别课程数据失败<br /><br />`, logFrame.repoLink());
        return "do not continue";
      }

      /* 顺便获取学期开始时间 */
      if (week === 1) {
        logFrame.log(`获取学期开始时间<br />`);

        try {
          const date = new Date(doc.querySelectorAll(".pkScheduleWeek")[0].childNodes[2].nodeValue.trim());  // 创建日期对象
          date.setDate(date.getDate() - 1);
          tryTimestamp = date.getTime().toString();

          if (tryTimestamp === undefined) {
            logFrame.log("获取学期开始时间失败, 将跳过获取学期开始时间<br />");
          }

        } catch (err) {
          logFrame.log(`获取学期开始时间失败: "${err?.message ?? err}", 将跳过获取学期开始时间<br />`);
        }
      }

    } catch (err) {
      logFrame.log(`<b>导入失败</b><br />识别课程数据失败: "${err?.message ?? err}"<br /><br />`, logFrame.repoLink());
      return "do not continue";
    }
    const coursesOfWeek = tryCoursesOfWeek ?? [];

    logFrame.log(`第${week}周共识别到${coursesOfWeek?.length}门课程 `, logFrame.copyButton(JSON.stringify(coursesOfWeek)), "<br />");

    /* 放入课程列表 */
    tryCourses.push(coursesOfWeek);

  }
  
  // 扁平化课程列表 (因为每周返回的是数组，push后是二维数组，需要flat)
  const courses = (tryCourses ?? []).flat();
  const timestamp = tryTimestamp;

  logFrame.log(`共识别到${courses?.length}门课程 `, logFrame.copyButton(JSON.stringify(courses)), "<br />");

  if (timestamp === undefined) {
    logFrame.log("没有获取到学期开始时间");
  } else {
    logFrame.log(`获取到学期开始时间: ${timestamp}<br />`);
  }

  /* 课程后处理 */
  let tryPostProcessings;
  try {
    // 修正：调用全局的 coursesPostProcessings
    tryPostProcessings = typeof coursesPostProcessings === 'function' ? coursesPostProcessings() : undefined;

    if (
      typeof tryPostProcessings?.mergeConflictsAndDuplicates !== "function" ||
      typeof tryPostProcessings?.mergeWeeks !== "function" ||
      typeof tryPostProcessings?.mergeTeachersOrPositions !== "function"
    ) {
      logFrame.log("初始化课程后处理失败, 将跳过课程后处理<br />");
      tryPostProcessings = undefined;
    }

  } catch (err) {
    logFrame.log(`初始化课程后处理失败: "${err?.message ?? err}", 将跳过课程后处理<br />`);
  }
  const postProcessings = tryPostProcessings ?? { mergeConflictsAndDuplicates: a => a, mergeWeeks: a => a, mergeTeachersOrPositions: a => a };

  const maxCourses = 150;

  logFrame.log("处理冲突课程<br />");
  let tryCourses1;
  try {
    tryCourses1 = postProcessings.mergeConflictsAndDuplicates(courses);

    if (tryCourses === undefined) {
      logFrame.log(`处理冲突课程失败, 将跳过处理冲突课程<br />`);
    }

  } catch (err) {
    logFrame.log(`处理冲突课程失败: "${err?.message ?? err}", 将跳过处理冲突课程<br />`);
  }
  const courses1 = tryCourses1 ?? courses;
  logFrame.log(`处理后还有${courses1?.length}门课程 `, logFrame.copyButton(JSON.stringify(courses1)), "<br />");

  logFrame.log("合并不同周数的相同课程<br />");
  let tryCourses2;
  try {
    tryCourses2 = postProcessings.mergeWeeks(courses1);

    if (tryCourses2 === undefined) {
      logFrame.log(`合并课程失败, 将跳过合并课程<br />`);
    }

  } catch (err) {
    logFrame.log(`合并课程失败: "${err?.message ?? err}", 将跳过合并课程<br />`);
  }
  const courses2 = tryCourses2 ?? courses1;
  logFrame.log(`合并后还有${courses2?.length}门课程 `, logFrame.copyButton(JSON.stringify(courses2)), "<br />");

  let tryCourses3;
  let courses3 = courses2;
  if (courses2?.length > maxCourses) {
    logFrame.log("合并不同教师/教室的相同课程<br />");
    try {
      tryCourses3 = postProcessings.mergeTeachersOrPositions(courses2, mergePositions = true, mergeTeachers = true);

      if (tryCourses3 === undefined) {
        logFrame.log(`合并课程失败, 将跳过合并课程<br />`);
      }

    } catch (err) {
      logFrame.log(`合并课程失败: "${err?.message ?? err}", 将跳过合并课程<br />`);
    }
    courses3 = tryCourses3 ?? courses2;
    logFrame.log(`合并后还有${courses3?.length}门课程 `, logFrame.copyButton(JSON.stringify(courses3)), "<br />");
  }

  /* 获取时间表 */
  logFrame.log("获取时间表<br />");
  let tryTimetable;
  try {
    tryTimetable = {};
    if (timestamp !== undefined) {
      tryTimetable.startSemester = timestamp;
    }

    if (tryTimetable === undefined) {
      logFrame.log(`获取时间表失败, 将跳过获取时间表<br />`);
    }

  } catch (err) {
    logFrame.log(`获取时间表失败: "${err?.message ?? err}", 将跳过获取时间表<br />`);
  }
  const timetable = tryTimetable ?? {};

  if (typeof timetable === "object" && Object.keys(timetable).length === 0) {
    logFrame.log("没有配置时间表 <br />");
  } else {
    logFrame.log(`获取到${timetable?.sections?.length}节课 `, logFrame.copyButton(JSON.stringify(timetable)), "<br />");
  }

  /* 完成导入 */
  logFrame.log("<br />", logFrame.repoLink(), "<br />3秒后完成导入...");
  await new Promise(e => setTimeout(e, 3000));
  return JSON.stringify({ courses: courses3, timetable: timetable });

}
