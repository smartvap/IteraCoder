import { QueryFileDto } from "./dto";
import service from "@/http";

type Res = any;

// 查询知识库文件列表
export const queryFileApi = async (params: QueryFileDto): Promise<Res> => {
  return service.get("/knowledge/contents", {
    params,
  });
};
