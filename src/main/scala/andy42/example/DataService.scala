package andy42.example

import zio.*
import java.sql.SQLException

trait DataService:
    def get(k: String): IO[SQLException, List[Int]]
    def put(k: String, v: List[Int]): IO[SQLException, Unit]

// TODO: Implement placeholder implementation for testing