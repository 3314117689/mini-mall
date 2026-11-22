package com.mall.common.exception;

import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.mall.common.result.Result;

import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理器：拦截所有 Controller 抛出的异常，统一转换成 Result 结构。
 *
 * 以前的做法：Service 返回 null → Controller 包成 Result.ok(null) → 前端拿到 code=0 但没数据，
 * 既不知道是"没有"还是"出错"，也没法给出友好提示。
 *
 * 现在的做法：Service 直接抛 BizException → 这里统一捕获 → 返回带错误码的 Result。 好处：Service 里不用写
 * try-catch，Controller 保持一行式，前端永远拿到同一种结构。
 *
 * L10：参数校验统一由 JSR303 声明，校验异常在这里收口成 1000，message 必须带字段名。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	/** 业务异常：预期内的失败，warn 级别即可，前端拿错误码展示提示 */
	@ExceptionHandler(BizException.class)
	public Result<Void> handleBizException(BizException e) {
		log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
		return Result.fail(e.getCode(), e.getMessage());
	}

	/** 请求体 @Valid 失败：每个 FieldError 拼成 "字段: 原因"，多个用分号隔开 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
		String detail = e.getBindingResult().getFieldErrors().stream()
				.map(this::formatFieldError)
				.collect(Collectors.joining("；"));
		return paramError(detail);
	}

	/** 查询参数/表单绑定失败（MethodArgumentNotValidException 的父类，这里兜住其他绑定入口） */
	@ExceptionHandler(BindException.class)
	public Result<Void> handleBindException(BindException e) {
		String detail = e.getBindingResult().getFieldErrors().stream()
				.map(this::formatFieldError)
				.collect(Collectors.joining("；"));
		return paramError(detail);
	}

	/** 方法参数校验失败（类上 @Validated + @RequestParam/@PathVariable 上的约束） */
	@ExceptionHandler(ConstraintViolationException.class)
	public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
		String detail = e.getConstraintViolations().stream()
				.map(v -> v.getPropertyPath() + ": " + v.getMessage())
				.collect(Collectors.joining("；"));
		return paramError(detail);
	}

	/** Spring 6.1+ 内置方法参数校验失败（Controller 参数上的约束，不经过 AOP） */
	@ExceptionHandler(HandlerMethodValidationException.class)
	public Result<Void> handleHandlerMethodValidation(HandlerMethodValidationException e) {
		String detail = e.getParameterValidationResults().stream()
				.flatMap(r -> r.getResolvableErrors().stream()
						.map(err -> r.getMethodParameter().getParameterName() + ": " + err.getDefaultMessage()))
				.collect(Collectors.joining("；"));
		return paramError(detail);
	}

	/** 缺少必填的查询参数，如 /order/page 不传 userId */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public Result<Void> handleMissingParameter(MissingServletRequestParameterException e) {
		return paramError("缺少必填参数: " + e.getParameterName());
	}

	/** 参数类型不匹配，如 userId=abc 转 Long */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		Class<?> requiredType = e.getRequiredType();
		String expected = requiredType == null ? "未知类型" : requiredType.getSimpleName();
		return paramError("参数 " + e.getName() + " 类型不正确，应为 " + expected);
	}

	/** 请求体不是合法 JSON（语法错误、字段类型对不上等） */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
		log.warn("请求体解析失败: {}", e.getMessage());
		return Result.fail(ErrorCode.PARAM_ERROR.getCode(), "请求体格式错误");
	}

	/**
	 * 静态资源/文档端点不存在：返回 404 空体。
	 * L10：生产 profile 关闭 Swagger 后，/v3/api-docs 就走这里，必须能给出 404 而不是 200。
	 */
	@ResponseStatus(HttpStatus.NOT_FOUND)
	@ExceptionHandler(NoResourceFoundException.class)
	public void handleNotFound(NoResourceFoundException e) {
		log.debug("资源不存在: {}", e.getResourcePath());
	}

	/** 兜底：未预期的异常，error 级别带堆栈，方便排查，但对前端隐藏细节 */
	@ExceptionHandler(Exception.class)
	public Result<Void> handleException(Exception e) {
		log.error("系统异常", e);
		return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMsg());
	}

	/** FieldError → "字段: 原因" */
	private String formatFieldError(FieldError error) {
		return error.getField() + ": " + error.getDefaultMessage();
	}

	/** 校验类异常统一出口：code=1000，message 为字段明细；明细为空时退回默认文案 */
	private Result<Void> paramError(String detail) {
		String message = detail == null || detail.isBlank() ? ErrorCode.PARAM_ERROR.getMsg() : detail;
		log.warn("参数校验失败: {}", message);
		return Result.fail(ErrorCode.PARAM_ERROR.getCode(), message);
	}
}
